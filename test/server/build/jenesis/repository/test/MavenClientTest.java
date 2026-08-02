package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.test.Requirement.requireOrSkip;

/**
 * Proves the {@link build.jenesis.repository.format.maven.MavenFormat} plugin against the real Apache Maven client,
 * which now runs from a pinned {@code maven} Docker image ({@link ToolContainer}) rather than an {@code mvn} the
 * developer had to install on the {@code PATH}. It boots a {@link RepositoryApplication} (a plain HTTP repository on an
 * ephemeral port, which {@code mvn} accepts because it is on {@code localhost}), deploys a modular jar with
 * {@code mvn deploy:deploy-file}, then resolves it back with {@code mvn dependency:get} into a clean local repository -
 * so a genuine Maven deploy/resolve round-trip exercises the layout end to end. The container joins host networking so
 * {@code mvn} reaches the host's ephemeral loopback port; the work directory is bind-mounted so the deployed jar and
 * the resolved local repository are shared with the test. The cross-published module view (the Jenesis-specific
 * {@code /module/} layout, which no Maven client knows) is checked over HTTP. Self-skips when no Docker is available.
 */
@Tag("maven")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MavenClientTest {

    private static final String IMAGE = "maven:3.9.9-eclipse-temurin-21";
    private static final String GROUP = "build.jenesis.repository.mavenclient";
    private static final String ARTIFACT = "widget";
    private static final String VERSION = "1.0";
    private static final String MODULE = "test.widget";

    @TempDir
    static Path store;
    @TempDir
    static Path work;

    private RepositoryApplication.Running running;
    private ToolContainer tool;
    private HttpClient client;
    private String base;
    private Path settings;
    private String lastOutput = "";

    @BeforeAll
    public void start() throws Exception {
        requireOrSkip(ToolContainer.dockerAvailable(), "Docker is required for the Maven client integration test");
        // Route plugin resolution through the same Central mirror the rest of the build uses (set on CI to dodge
        // Central rate limits); unset locally, so mvn falls back to Central directly.
        String mirror = System.getenv("MAVEN_REPOSITORY_URI");
        if (mirror != null && !mirror.isBlank()) {
            settings = work.resolve("settings.xml");
            Files.writeString(settings, "<settings><mirrors><mirror>"
                    + "<id>central-mirror</id><mirrorOf>central</mirrorOf><url>" + mirror + "</url>"
                    + "</mirror></mirrors></settings>");
        }
        System.setProperty("jenesis.repository.insecure", "true");
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository/";
        tool = ToolContainer.start(IMAGE, work);
    }

    @AfterAll
    public void stop() {
        if (tool != null) {
            tool.close();
        }
        if (running != null) {
            running.close();
        }
        System.clearProperty("jenesis.repository.insecure");
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void a_modular_jar_deploys_and_resolves_with_maven_and_cross_publishes() throws Exception {
        Path jar = work.resolve(ARTIFACT + "-" + VERSION + ".jar");
        Files.write(jar, automaticModuleJar(MODULE));
        Path local = work.resolve("m2");

        // deploy the jar (and a generated pom) to the repository with the real Maven client.
        assertThat(mvn(300, "-B", "deploy:deploy-file",
                "-Dfile=" + inContainer(jar),
                "-DgroupId=" + GROUP, "-DartifactId=" + ARTIFACT, "-Dversion=" + VERSION, "-Dpackaging=jar",
                "-DgeneratePom=true",
                "-DrepositoryId=jenesis", "-Durl=" + base + "maven/",
                "-Dmaven.repo.local=" + inContainer(local)))
                .as("mvn deploy: " + lastOutput).isZero();

        // resolve it back with the real Maven client; the jar was deployed to the server, not installed locally, so a
        // dependency:get into that same local repo must fetch it from the server.
        assertThat(mvn(300, "-B", "dependency:get",
                "-Dartifact=" + GROUP + ":" + ARTIFACT + ":" + VERSION + ":jar",
                "-DremoteRepositories=jenesis::::" + base + "maven/",
                "-Dtransitive=false",
                "-Dmaven.repo.local=" + inContainer(local)))
                .as("mvn dependency:get: " + lastOutput).isZero();

        Path resolved = local.resolve(GROUP.replace('.', '/')).resolve(ARTIFACT).resolve(VERSION)
                .resolve(ARTIFACT + "-" + VERSION + ".jar");
        assertThat(Files.exists(resolved)).as("mvn resolved the jar from the repository into the local repo").isTrue();
        assertThat(Files.readAllBytes(resolved)).as("the resolved jar is byte-for-byte the deployed one")
                .isEqualTo(Files.readAllBytes(jar));

        // the cross-published module view - the Jenesis /module/ layout no Maven client speaks - checked over HTTP.
        HttpResponse<byte[]> module = client.send(HttpRequest.newBuilder(
                        URI.create(base + "module/" + MODULE + "/" + VERSION + "/" + MODULE + ".jar")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(module.statusCode()).as("the modular jar cross-published into the module layout").isEqualTo(200);
        assertThat(module.body()).isEqualTo(Files.readAllBytes(jar));
    }

    private int mvn(int timeoutSeconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("mvn");
        if (settings != null) {
            command.add("-s");
            command.add(inContainer(settings));
        }
        Collections.addAll(command, arguments);
        ToolContainer.Result result = tool.exec(Duration.ofSeconds(timeoutSeconds), command.toArray(new String[0]));
        lastOutput = result.output();
        return result.exitCode();
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
