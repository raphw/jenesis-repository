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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.test.Requirement.requireOrSkip;

/**
 * Verifies that Helm works against the repository with no Helm-specific code: a Helm 3 chart is an OCI artifact, so
 * {@code helm push} and {@code helm pull} go through the {@link build.jenesis.repository.format.oci.OciFormat} {@code /v2/}
 * registry. The test boots a {@link RepositoryApplication} with the OCI plugin, packages a chart, pushes it over plain
 * HTTP, then pulls it back into a clean directory. The suite skips itself when {@code helm} is unavailable.
 */
@Tag("helm")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HelmOciTest {

    private static final String HELM = "helm";

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private Map<String, String> env;
    private String lastOutput = "";

    @BeforeAll
    public void start() {
        requireOrSkip(commandAvailable(HELM), "helm is required for the Helm-over-OCI verification");
        System.setProperty("JENESIS_STORE_ROOT", root.resolve("store").toString());
        running = RepositoryApplication.start(0);
        env = Map.of(
                "HELM_CACHE_HOME", root.resolve("helm-cache").toString(),
                "HELM_CONFIG_HOME", root.resolve("helm-config").toString(),
                "HELM_DATA_HOME", root.resolve("helm-data").toString());
    }

    @AfterAll
    public void stop() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void a_chart_pushes_and_pulls_over_the_oci_registry() throws Exception {
        Path chart = Files.createDirectories(root.resolve("jenesis-chart"));
        Files.createDirectories(chart.resolve("templates"));
        Files.writeString(chart.resolve("Chart.yaml"),
                "apiVersion: v2\nname: jenesis-chart\nversion: 0.1.0\ndescription: a helm-over-oci demo\n");

        assertThat(helm(60, root, "package", chart.toString(), "-d", root.toString()))
                .as("helm package: " + lastOutput).isZero();
        Path packaged = root.resolve("jenesis-chart-0.1.0.tgz");

        String registry = "oci://localhost:" + running.port() + "/charts";
        assertThat(helm(60, root, "push", packaged.toString(), registry, "--plain-http"))
                .as("helm push: " + lastOutput).isZero();

        Path destination = Files.createDirectories(root.resolve("pulled"));
        assertThat(helm(60, destination, "pull", registry + "/jenesis-chart", "--version", "0.1.0", "--plain-http"))
                .as("helm pull: " + lastOutput).isZero();
        assertThat(Files.exists(destination.resolve("jenesis-chart-0.1.0.tgz")))
                .as("the chart pulled back from the OCI registry").isTrue();
    }

    /** Whether the helm client is on the PATH (locally and on CI); the test self-skips when it is not. */
    private static boolean commandAvailable(String command) {
        try {
            new ProcessBuilder(command, "version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int helm(int timeoutSeconds, Path cwd, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(HELM);
        Collections.addAll(command, arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(captured);
            } catch (IOException ignored) {
                // the stream closes when the process exits
            }
        });
        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        drain.join(Duration.ofSeconds(5));
        lastOutput = captured.toString(StandardCharsets.UTF_8);
        if (!exited) {
            throw new IOException("helm timed out");
        }
        return process.exitValue();
    }
}
