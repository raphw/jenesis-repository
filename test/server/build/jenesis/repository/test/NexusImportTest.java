package build.jenesis.repository.test;

import build.jenesis.repository.proxy.HttpFetcher;
import build.jenesis.repository.importer.maven.MavenSource;
import build.jenesis.repository.importer.nexus.NexusSource;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.test.Requirement.requireOrSkip;

/**
 * Proves the {@link NexusSource} importer against a real Sonatype Nexus, not a hand-written fake. It boots the
 * {@code sonatype/nexus3} OSS image with Testcontainers, publishes a modular jar into its {@code maven-releases} repo
 * with the real {@code mvn} client (so the components the importer reads are exactly what Maven produces), then walks
 * the live Components REST API with {@link RepositoryImport}, migrating into a filesystem store. The migrated store is
 * served by a real {@link RepositoryApplication} and the artifacts are pulled back over HTTP - the jar (byte for byte),
 * its pom, and the cross-published {@code /module/} view. The same seeded repository is then walked again with the
 * vendor-neutral {@link MavenSource} over the HTML directory index Nexus serves - no vendor API - proving the generic
 * tree walk against the real thing. The {@code mvn} client runs from a pinned image ({@link ToolContainer}), so the
 * test needs no host Maven. Tagged {@code nexus}; self-skips when Docker is absent.
 *
 * <p>The image is pinned to a pre-Community-Edition OSS release (3.70.x): from 3.79 the default {@code sonatype/nexus3}
 * is the Community Edition, which gates writes behind an onboarding/activation step and so cannot be seeded headlessly.
 */
@Tag("nexus")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NexusImportTest {

    private static final String IMAGE = "sonatype/nexus3:3.70.4@sha256:21e3ecb4f2287e2939be6b6bdbfb8676c764a17b15df44be1ceb31f18de63bb3";
    private static final String MAVEN_IMAGE = "maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e";
    private static final String GROUP = "org.example";
    private static final String ARTIFACT = "lib";
    private static final String VERSION = "1.0";
    private static final String MODULE = "test.lib";

    @TempDir
    static Path root;
    @TempDir
    static Path work;

    private GenericContainer<?> container;
    private ToolContainer tool;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private String nexus;
    private String password;
    private byte[] jar;
    private RepositoryImport.Result result;

    @BeforeAll
    public void start() throws Exception {
        requireOrSkip(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the Nexus import test");
        client = HttpClient.newHttpClient();

        // boot a real Nexus (OSS edition) and wait for it to come up.
        container = new GenericContainer<>(IMAGE)
                .withExposedPorts(8081)
                .waitingFor(Wait.forHttp("/service/rest/v1/status").forPort(8081)
                        .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(4)));
        container.start();
        nexus = "http://" + container.getHost() + ":" + container.getMappedPort(8081);
        password = container.execInContainer("cat", "/nexus-data/admin.password").getStdout().strip();

        // seed it with the real Maven client, run from a pinned image over host networking so it reaches Nexus'
        // mapped port: deploy a modular jar into the default maven-releases repo.
        jar = automaticModuleJar(MODULE);
        Path file = work.resolve(ARTIFACT + "-" + VERSION + ".jar");
        Files.write(file, jar);
        Path settings = work.resolve("settings.xml");
        Files.writeString(settings, settings(password));
        tool = ToolContainer.start(MAVEN_IMAGE, work);
        assertThat(tool.exec(Duration.ofSeconds(300), "mvn", "-B", "-s", inContainer(settings),
                "-Dmaven.repo.local=" + inContainer(work.resolve("m2")), "deploy:deploy-file",
                "-Dfile=" + inContainer(file), "-DgroupId=" + GROUP, "-DartifactId=" + ARTIFACT,
                "-Dversion=" + VERSION, "-Dpackaging=jar", "-DgeneratePom=true",
                "-DrepositoryId=nexus", "-Durl=" + nexus + "/repository/maven-releases/").exitCode())
                .as("mvn deploy to Nexus").isZero();

        // migrate maven-releases from the live Components API into a filesystem store, then serve that store.
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        // Import into the default/default artifact space, the doubly-scoped layout the server serves from.
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null)
                .scope("default").scope("default");
        result = new RepositoryImport().run(new NexusSource(URI.create(nexus), "maven-releases", new HttpFetcher())
                .withCredentials("admin", password), store);
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        running = RepositoryApplication.start(0);
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void stop() throws Exception {
        if (tool != null) {
            tool.close();
        }
        if (running != null) {
            running.close();
        }
        if (container != null) {
            container.stop();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void a_maven_repo_seeded_with_mvn_is_imported_from_nexus_and_served() throws Exception {
        assertThat(result.imported()).as("assets migrated from Nexus").isPositive();
        assertThat(result.skippedFormats()).as("maven2 is a supported format").isEmpty();

        assertThat(get("/maven/" + GROUP.replace('.', '/') + "/" + ARTIFACT + "/" + VERSION
                + "/" + ARTIFACT + "-" + VERSION + ".jar"))
                .as("the jar imported from Nexus, byte for byte").isEqualTo(jar);
        assertThat(new String(get("/maven/" + GROUP.replace('.', '/') + "/" + ARTIFACT + "/" + VERSION
                + "/" + ARTIFACT + "-" + VERSION + ".pom"), StandardCharsets.UTF_8)).contains("modelVersion");
        assertThat(get("/module/" + MODULE + "/" + VERSION + "/" + MODULE + ".jar"))
                .as("the modular jar cross-published into the module layout").isEqualTo(jar);
    }

    @Test
    public void the_same_repo_is_walked_vendor_neutrally_over_the_directory_listing() throws Exception {
        // The generic Maven source needs no vendor API: it walks the HTML directory index Nexus serves on the
        // repository's browse URL - the vendor-neutral proof against the real thing.
        List<String> paths = new ArrayList<>();
        Map<String, byte[]> downloaded = new HashMap<>();
        new MavenSource(URI.create(nexus + "/repository"), "maven-releases", new HttpFetcher())
                .withCredentials("admin", password)
                .forEach((format, path, content) -> {
                    assertThat(format).isEqualTo("maven");
                    paths.add(path);
                    if (path.endsWith(".jar")) {
                        try (InputStream in = content.open()) {
                            downloaded.put(path, in.readAllBytes());
                        }
                    }
                }, cursor -> { });
        String prefix = GROUP.replace('.', '/') + "/" + ARTIFACT + "/" + VERSION + "/" + ARTIFACT + "-" + VERSION;
        assertThat(paths).contains(prefix + ".jar", prefix + ".pom");
        assertThat(downloaded.get(prefix + ".jar"))
                .as("the jar walked from the Nexus directory listing, byte for byte").isEqualTo(jar);
    }

    private static String settings(String password) {
        StringBuilder settings = new StringBuilder("<settings><servers><server><id>nexus</id>"
                + "<username>admin</username><password>" + password + "</password></server></servers>");
        // Route plugin resolution through the same Central mirror the rest of the build uses when it is set (CI).
        String mirror = System.getenv("MAVEN_REPOSITORY_URI");
        if (mirror != null && !mirror.isBlank()) {
            settings.append("<mirrors><mirror><id>central-mirror</id><mirrorOf>central</mirrorOf><url>")
                    .append(mirror).append("</url></mirror></mirrors>");
        }
        return settings.append("</settings>").toString();
    }

    private byte[] get(String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
        return response.body();
    }

    /** The path a work-directory file has inside the client container, where {@code work} is bind-mounted at /work. */
    private static String inContainer(Path path) {
        return "/work/" + work.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static byte[] automaticModuleJar(String moduleName) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry(moduleName.replace('.', '/') + "/Marker.class"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
