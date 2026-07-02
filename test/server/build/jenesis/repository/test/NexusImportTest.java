package build.jenesis.repository.test;

import build.jenesis.repository.importer.nexus.NexusSource;
import build.jenesis.repository.server.PullThroughCache;
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
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the {@link NexusSource} importer against a real Sonatype Nexus, not a hand-written fake. It boots the
 * {@code sonatype/nexus3} OSS image with the docker CLI, publishes a modular jar into its {@code maven-releases} repo
 * with the real {@code mvn} client (so the components the importer reads are exactly what Maven produces), then walks
 * the live Components REST API with {@link RepositoryImport}, migrating into a filesystem store. The migrated store is
 * served by a real {@link RepositoryApplication} and the artifacts are pulled back over HTTP - the jar (byte for byte),
 * its pom, and the cross-published {@code /module/} view. Tagged {@code nexus}; self-skips when docker or mvn is
 * absent.
 *
 * <p>The image is pinned to a pre-Community-Edition OSS release (3.70.x): from 3.79 the default {@code sonatype/nexus3}
 * is the Community Edition, which gates writes behind an onboarding/activation step and so cannot be seeded headlessly.
 */
@Tag("nexus")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NexusImportTest {

    private static final String IMAGE = "sonatype/nexus3:3.70.4";
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
        assumeTrue(exec(30, null, "docker", "version").code() == 0, "Docker is required for the Nexus import test");
        assumeTrue(exec(30, null, "mvn", "-v").code() == 0, "Apache Maven (mvn) is required for the Nexus import test");
        client = HttpClient.newHttpClient();

        // boot a real Nexus (OSS edition) and wait for it to come up.
        container = expect(exec(300, null, "docker", "run", "-d", "-p", "0:8081", IMAGE), "docker run");
        String nexus = "http://localhost:" + mappedPort(container, "8081");
        awaitReady(nexus + "/service/rest/v1/status");
        String password = expect(exec(30, null, "docker", "exec", container, "cat", "/nexus-data/admin.password"),
                "read admin password");

        // seed it with the real Maven client: deploy a modular jar into the default maven-releases repo.
        jar = automaticModuleJar(MODULE);
        Path file = work.resolve(ARTIFACT + "-" + VERSION + ".jar");
        Files.write(file, jar);
        Path settings = work.resolve("settings.xml");
        Files.writeString(settings, settings(password));
        assertThat(exec(300, work, "mvn", "-B", "-s", settings.toString(),
                "-Dmaven.repo.local=" + work.resolve("m2"), "deploy:deploy-file",
                "-Dfile=" + file, "-DgroupId=" + GROUP, "-DartifactId=" + ARTIFACT, "-Dversion=" + VERSION,
                "-Dpackaging=jar", "-DgeneratePom=true",
                "-DrepositoryId=nexus", "-Durl=" + nexus + "/repository/maven-releases/").code())
                .as("mvn deploy to Nexus").isZero();

        // migrate maven-releases from the live Components API into a filesystem store, then serve that store.
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        result = new RepositoryImport().run(new NexusSource(URI.create(nexus), "maven-releases", PullThroughCache.http())
                .withCredentials("admin", password), store);
        running = RepositoryApplication.start(0);
        base = "http://localhost:" + running.port();
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

    private void awaitReady(String statusUrl) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 90; attempt++) {
            try {
                HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create(statusUrl))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException stillStarting) {
                // Nexus is not accepting connections yet.
            }
            Thread.sleep(2000);
        }
        throw new IOException("Nexus did not become ready at " + statusUrl);
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
            throw new IOException(what + " failed (" + result.code() + "): " + result.output());
        }
        return result.output();
    }

    private record Exec(int code, String output) {
    }

    private Exec exec(int timeoutSeconds, Path cwd, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(captured);
            } catch (IOException ignored) {
                // The stream closes when the process exits; a read error here is not actionable.
            }
        });
        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        drain.join(Duration.ofSeconds(5));
        if (!exited) {
            throw new IOException(command[0] + " timed out");
        }
        return new Exec(process.exitValue(), captured.toString(StandardCharsets.UTF_8).trim());
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
