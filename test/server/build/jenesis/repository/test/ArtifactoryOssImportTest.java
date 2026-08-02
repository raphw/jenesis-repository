package build.jenesis.repository.test;

import build.jenesis.repository.proxy.HttpFetcher;
import build.jenesis.repository.importer.artifactory.ArtifactorySource;
import build.jenesis.repository.importer.maven.MavenSource;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.github.dockerjava.api.model.Ulimit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.test.Requirement.requireOrSkip;

/**
 * Proves the {@link ArtifactorySource} importer against a real, free JFrog Artifactory - exercising the OSS fallback,
 * not a fake. It boots the {@code artifactory-oss} image with Testcontainers and seeds its default local repo with a
 * real {@code mvn deploy}, then migrates it with {@link RepositoryImport}. A free instance gates the deep File List API
 * behind Pro (a {@code 400}), so the walk falls back to the OSS-available per-folder Folder Info crawl - this test is
 * the end-to-end proof that the fallback is seamless. The migrated store is served by a real
 * {@link RepositoryApplication} and the artifacts pulled back over HTTP (the jar byte for byte, its pom, and the
 * cross-published {@code /module/} view). The same seeded repository is then walked again with the vendor-neutral
 * {@link MavenSource} over the HTML directory index Artifactory serves - no vendor API at all - proving the generic
 * tree walk against the real thing. The {@code mvn} client runs from a pinned image ({@link ToolContainer}), so the
 * test needs no host Maven. Tagged {@code artifactory}; self-skips when Docker is absent (or the host's nofile hard
 * limit is too low for Artifactory 6.x to boot).
 *
 * <p>The image is pinned to an OSS release; the raised {@code nofile} ulimit is mandatory - Artifactory 6.x refuses to
 * boot on the default 1024.
 */
@Tag("artifactory")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ArtifactoryOssImportTest {

    private static final String IMAGE = "releases-docker.jfrog.io/jfrog/artifactory-oss:6.23.13@sha256:01604c310953da0feb1748ab0d83e90fa36516f3344187efa61f888f67b8ea98";
    private static final String AUTH = "Basic "
            + Base64.getEncoder().encodeToString("admin:password".getBytes(StandardCharsets.UTF_8));
    // Artifactory OSS gates the Repository Configuration API behind Pro too, so seed the default local repo that ships
    // with the image rather than creating one; mvn deploy writes the maven-layout paths into it regardless of its type.
    private static final String REPO = "example-repo-local";
    private static final String GROUP = "org.example";
    private static final String ARTIFACT = "lib";
    private static final String VERSION = "1.0";
    private static final String MODULE = "test.lib";

    @TempDir
    static Path root;
    @TempDir
    static Path work;

    private static final String MAVEN_IMAGE = "maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e";

    private GenericContainer<?> container;
    private ToolContainer tool;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private String upstream;
    private byte[] jar;
    private RepositoryImport.Result result;

    /** Artifactory refuses to boot unless it can raise nofile well above the default, and a container can never
     *  exceed the host's hard limit - so on a host whose {@code ulimit -Hn} is below {@code minimum} the container
     *  only ever reaches "created" and never starts. Detect that and skip (as a missing Docker daemon skips) rather
     *  than fail. If the probe itself cannot determine the limit, treat it as sufficient so a normal CI host still
     *  runs the test. */
    private boolean openFilesHardLimitAtLeast(int minimum) {
        try {
            Exec limit = exec(10, null, "bash", "-lc", "ulimit -Hn");
            if (limit.code() != 0) {
                return true;
            }
            String value = limit.stdout().strip();
            return value.equals("unlimited") || (value.matches("\\d+") && Long.parseLong(value) >= minimum);
        } catch (IOException probeFailed) {
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @BeforeAll
    public void start() throws Exception {
        requireOrSkip(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the Artifactory import test");
        requireOrSkip(openFilesHardLimitAtLeast(32768),
                "Artifactory 6.x refuses to boot unless nofile is raised well above the default, and a container "
                        + "cannot exceed the host's open-files hard limit; this environment caps it below 32768, so "
                        + "the container never starts here (as it would on any CI host with a sufficient ulimit)");
        client = HttpClient.newHttpClient();

        // boot a real Artifactory (OSS edition) and wait for it to come up.
        container = new GenericContainer<>(IMAGE)
                .withExposedPorts(8081)
                // Artifactory 6.x refuses to boot on the default nofile=1024 ulimit; raise it as the docker-run flag did.
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withUlimits(new Ulimit[]{new Ulimit("nofile", 32768L, 32768L)}))
                .waitingFor(Wait.forHttp("/artifactory/api/system/ping").forPort(8081)
                        .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(4)));
        container.start();
        upstream = "http://" + container.getHost() + ":" + container.getMappedPort(8081) + "/artifactory";
        awaitReady(upstream + "/api/system/ping");

        // seed the default local repo with the real Maven client.
        jar = automaticModuleJar(MODULE);
        Path file = work.resolve(ARTIFACT + "-" + VERSION + ".jar");
        Files.write(file, jar);
        Path settings = work.resolve("settings.xml");
        Files.writeString(settings, settings());
        tool = ToolContainer.start(MAVEN_IMAGE, work);
        assertThat(tool.exec(Duration.ofSeconds(300), "mvn", "-B", "-s", inContainer(settings),
                "-Dmaven.repo.local=" + inContainer(work.resolve("m2")), "deploy:deploy-file",
                "-Dfile=" + inContainer(file), "-DgroupId=" + GROUP, "-DartifactId=" + ARTIFACT,
                "-Dversion=" + VERSION, "-Dpackaging=jar", "-DgeneratePom=true",
                "-DrepositoryId=artifactory", "-Durl=" + upstream + "/" + REPO + "/").exitCode())
                .as("mvn deploy to Artifactory").isZero();

        // migrate the repo - the deep File List API is Pro-gated on OSS, so this exercises the Folder Info crawl - then
        // serve the migrated store.
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        // Import into the default/default artifact space, the doubly-scoped layout the server serves from.
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null)
                .scope("default").scope("default");
        result = new RepositoryImport().run(new ArtifactorySource(URI.create(upstream), REPO, "maven",
                new HttpFetcher()).withCredentials("admin", "password"), store);
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
    public void a_maven_repo_seeded_with_mvn_is_imported_via_the_folder_crawl_and_served() throws Exception {
        assertThat(result.imported()).as("assets migrated from Artifactory via the folder crawl").isPositive();
        assertThat(result.skippedFormats()).as("maven2 is a supported format").isEmpty();

        assertThat(get("/maven/" + GROUP.replace('.', '/') + "/" + ARTIFACT + "/" + VERSION
                + "/" + ARTIFACT + "-" + VERSION + ".jar"))
                .as("the jar imported from Artifactory, byte for byte").isEqualTo(jar);
        assertThat(new String(get("/maven/" + GROUP.replace('.', '/') + "/" + ARTIFACT + "/" + VERSION
                + "/" + ARTIFACT + "-" + VERSION + ".pom"), StandardCharsets.UTF_8)).contains("modelVersion");
        assertThat(get("/module/" + MODULE + "/" + VERSION + "/" + MODULE + ".jar"))
                .as("the modular jar cross-published into the module layout").isEqualTo(jar);
    }

    @Test
    public void the_same_repo_is_walked_vendor_neutrally_over_the_directory_listing() throws Exception {
        // The generic Maven source needs no vendor API - and no Pro edition: it walks the HTML directory index
        // Artifactory serves on the repository's browse URL - the vendor-neutral proof against the real thing.
        List<String> paths = new ArrayList<>();
        Map<String, byte[]> downloaded = new HashMap<>();
        new MavenSource(URI.create(upstream), REPO, new HttpFetcher())
                .withCredentials("admin", "password")
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
                .as("the jar walked from the Artifactory directory listing, byte for byte").isEqualTo(jar);
    }

    private static String settings() {
        StringBuilder settings = new StringBuilder("<settings><servers><server><id>artifactory</id>"
                + "<username>admin</username><password>password</password></server></servers>");
        // Route plugin resolution through the same Central mirror the rest of the build uses when it is set (CI).
        String mirror = System.getenv("MAVEN_REPOSITORY_URI");
        if (mirror != null && !mirror.isBlank()) {
            settings.append("<mirrors><mirror><id>central-mirror</id><mirrorOf>central</mirrorOf><url>")
                    .append(mirror).append("</url></mirror></mirrors>");
        }
        return settings.append("</settings>").toString();
    }

    private void awaitReady(String pingUrl) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 120; attempt++) {
            try {
                HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create(pingUrl))
                        .header("Authorization", AUTH).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException stillStarting) {
                // Artifactory is not accepting connections yet.
            }
            Thread.sleep(2000);
        }
        throw new IOException("Artifactory did not become ready at " + pingUrl);
    }

    private byte[] get(String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
        return response.body();
    }

    // Standard out and error are captured separately: an image pull's progress - and a platform-mismatch
    // warning on a runner whose architecture differs from the amd64-only image - go to stderr, and merging
    // them into stdout would corrupt the container id and port mapping read back from these commands.
    private record Exec(int code, String stdout, String stderr) {
        String diagnostic() {
            return stderr.isBlank() ? stdout : stderr;
        }
    }

    private Exec exec(int timeoutSeconds, Path cwd, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread drainOut = drain(process.getInputStream(), out);
        Thread drainErr = drain(process.getErrorStream(), err);
        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        drainOut.join(Duration.ofSeconds(5));
        drainErr.join(Duration.ofSeconds(5));
        if (!exited) {
            throw new IOException(command[0] + " timed out");
        }
        return new Exec(process.exitValue(),
                out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Thread drain(InputStream stream, ByteArrayOutputStream sink) {
        return Thread.ofVirtual().start(() -> {
            try (stream) {
                stream.transferTo(sink);
            } catch (IOException ignored) {
                // The stream closes when the process exits; a read error here is not actionable.
            }
        });
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
