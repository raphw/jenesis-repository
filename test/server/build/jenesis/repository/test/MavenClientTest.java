package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the {@link build.jenesis.repository.format.maven.MavenFormat} plugin against the real Apache Maven client: it
 * boots a {@link RepositoryApplication} (a plain HTTP repository on an ephemeral port, which {@code mvn} accepts
 * because it is on {@code localhost}), deploys a modular jar with {@code mvn deploy:deploy-file}, then resolves it back
 * with {@code mvn dependency:get} into a clean local repository - so a genuine Maven deploy/resolve round-trip
 * exercises the layout end to end. The cross-published module view (the Jenesis-specific {@code /module/} layout, which
 * no Maven client knows) is checked over HTTP. Tagged {@code maven}; self-skips when no {@code mvn} is on the PATH.
 */
@Tag("maven")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MavenClientTest {

    private static final String GROUP = "build.jenesis.repository.mavenclient";
    private static final String ARTIFACT = "widget";
    private static final String VERSION = "1.0";
    private static final String MODULE = "test.widget";

    @TempDir
    static Path store;
    @TempDir
    static Path work;

    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private Path settings;
    private String lastOutput = "";

    @BeforeAll
    public void start() throws Exception {
        assumeTrue(mvn(30, "-v") == 0, "Apache Maven (mvn) is required for the Maven client integration test");
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
        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository/";
    }

    @AfterAll
    public void stop() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("jenesis.repository.insecure");
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void a_modular_jar_deploys_and_resolves_with_maven_and_cross_publishes() throws Exception {
        Path jar = work.resolve(ARTIFACT + "-" + VERSION + ".jar");
        Files.write(jar, automaticModuleJar(MODULE));
        Path local = work.resolve("m2");

        // deploy the jar (and a generated pom) to the repository with the real Maven client.
        assertThat(mvn(300, "-B", "deploy:deploy-file",
                "-Dfile=" + jar,
                "-DgroupId=" + GROUP, "-DartifactId=" + ARTIFACT, "-Dversion=" + VERSION, "-Dpackaging=jar",
                "-DgeneratePom=true",
                "-DrepositoryId=jenesis", "-Durl=" + base + "maven/",
                "-Dmaven.repo.local=" + local))
                .as("mvn deploy: " + lastOutput).isZero();

        // resolve it back with the real Maven client; the jar was deployed to the server, not installed locally, so a
        // dependency:get into that same local repo must fetch it from the server.
        assertThat(mvn(300, "-B", "dependency:get",
                "-Dartifact=" + GROUP + ":" + ARTIFACT + ":" + VERSION + ":jar",
                "-DremoteRepositories=jenesis::::" + base + "maven/",
                "-Dtransitive=false",
                "-Dmaven.repo.local=" + local))
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
            command.add(settings.toString());
        }
        Collections.addAll(command, arguments);
        Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
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
        lastOutput = captured.toString(StandardCharsets.UTF_8);
        if (!exited) {
            throw new IOException("mvn " + String.join(" ", arguments) + " timed out");
        }
        return process.exitValue();
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
