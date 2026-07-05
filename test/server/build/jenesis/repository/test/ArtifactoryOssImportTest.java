package build.jenesis.repository.test;

import build.jenesis.repository.proxy.HttpFetcher;
import build.jenesis.repository.importer.artifactory.ArtifactorySource;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.test.Requirement.requireOrSkip;

/**
 * Proves the {@link ArtifactorySource} importer against a real, free JFrog Artifactory - exercising the OSS fallback,
 * not a fake. It boots the {@code artifactory-oss} image with the docker CLI and seeds its default local repo with a
 * real {@code mvn deploy}, then migrates it with {@link RepositoryImport}. A free instance gates the deep File List API
 * behind Pro (a {@code 400}), so the walk falls back to the OSS-available per-folder Folder Info crawl - this test is
 * the end-to-end proof that the fallback is seamless. The migrated store is served by a real
 * {@link RepositoryApplication} and the artifacts pulled back over HTTP (the jar byte for byte, its pom, and the
 * cross-published {@code /module/} view). Tagged {@code artifactory}; self-skips when docker or mvn is absent.
 *
 * <p>The image is pinned to an OSS release; the raised {@code nofile} ulimit is mandatory - Artifactory 6.x refuses to
 * boot on the default 1024.
 */
@Tag("artifactory")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ArtifactoryOssImportTest {

    private static final String IMAGE = "releases-docker.jfrog.io/jfrog/artifactory-oss:6.23.13";
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

    private String container;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private byte[] jar;
    private RepositoryImport.Result result;

    @BeforeAll
    public void start() throws Exception {
        requireOrSkip(exec(30, null, "docker", "version").code() == 0, "Docker is required for the Artifactory import test");
        requireOrSkip(exec(30, null, "mvn", "-v").code() == 0, "Apache Maven (mvn) is required for the Artifactory import test");
        client = HttpClient.newHttpClient();

        // boot a real Artifactory (OSS edition) and wait for it to come up.
        container = expect(exec(300, null, "docker", "run", "-d", "--ulimit", "nofile=32768:32768",
                "-p", "0:8081", IMAGE), "docker run");
        String upstream = "http://localhost:" + mappedPort(container, "8081") + "/artifactory";
        awaitReady(upstream + "/api/system/ping");

        // seed the default local repo with the real Maven client.
        jar = automaticModuleJar(MODULE);
        Path file = work.resolve(ARTIFACT + "-" + VERSION + ".jar");
        Files.write(file, jar);
        Path settings = work.resolve("settings.xml");
        Files.writeString(settings, settings());
        assertThat(exec(300, work, "mvn", "-B", "-s", settings.toString(),
                "-Dmaven.repo.local=" + work.resolve("m2"), "deploy:deploy-file",
                "-Dfile=" + file, "-DgroupId=" + GROUP, "-DartifactId=" + ARTIFACT, "-Dversion=" + VERSION,
                "-Dpackaging=jar", "-DgeneratePom=true",
                "-DrepositoryId=artifactory", "-Durl=" + upstream + "/" + REPO + "/").code())
                .as("mvn deploy to Artifactory").isZero();

        // migrate the repo - the deep File List API is Pro-gated on OSS, so this exercises the Folder Info crawl - then
        // serve the migrated store.
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        result = new RepositoryImport().run(new ArtifactorySource(URI.create(upstream), REPO, "maven",
                new HttpFetcher()).withCredentials("admin", "password"), store);
        running = RepositoryApplication.start(0);
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void stop() throws Exception {
        if (running != null) {
            running.close();
        }
        if (container != null) {
            exec(60, null, "docker", "rm", "-f", container);
        }
        System.clearProperty("JENESIS_STORE_ROOT");
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

    private String mappedPort(String container, String containerPort) throws IOException, InterruptedException {
        String mapping = expect(exec(30, null, "docker", "port", container, containerPort), "docker port");
        String first = mapping.lines().findFirst().orElseThrow();
        return first.substring(first.lastIndexOf(':') + 1);
    }

    private byte[] get(String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
        return response.body();
    }

    private static String expect(Exec result, String what) throws IOException {
        if (result.code() != 0) {
            throw new IOException(what + " failed (" + result.code() + "): " + result.diagnostic());
        }
        return result.stdout().strip();
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
