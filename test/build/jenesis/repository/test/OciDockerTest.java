package build.jenesis.repository.test;

import build.jenesis.repository.RepositoryApplication;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the {@link build.jenesis.repository.format.oci.OciFormat} plugin against the real {@code docker} client: it
 * boots a {@link RepositoryApplication} (a plain HTTP registry on an ephemeral port, which {@code docker} accepts
 * because it is on {@code localhost}), then pushes a tiny image to it and pulls it back after deleting the local
 * copies, so the round-trip genuinely exercises the registry's blob and manifest serving end to end. The suite
 * skips itself when no Docker daemon is reachable, so a checkout without Docker still builds green.
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OciDockerTest {

    private static final String SOURCE = "hello-world";

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private String registry;
    private String lastOutput = "";

    @BeforeAll
    public void start() {
        assumeTrue(dockerAvailable(), "Docker is required for the OCI (docker push/pull) integration test");
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        running = RepositoryApplication.start(0);
        registry = "localhost:" + running.port();
    }

    @AfterAll
    public void stop() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void a_real_image_pushes_and_pulls_back() throws Exception {
        String reference = registry + "/jenesis/hello:latest";
        assertThat(docker(300, "pull", SOURCE)).as("pull the source image").isZero();
        assertThat(docker(60, "tag", SOURCE, reference)).isZero();

        assertThat(docker(180, "push", reference)).as("push to the Jenesis registry: " + lastOutput).isZero();

        // Remove every local copy so the pull must fetch the manifest, config and layer from the registry.
        docker(60, "image", "rm", "-f", reference);
        docker(60, "image", "rm", "-f", SOURCE);

        assertThat(docker(180, "pull", reference)).as("pull back from the Jenesis registry: " + lastOutput).isZero();

        docker(60, "image", "rm", "-f", reference);
    }

    private boolean dockerAvailable() {
        try {
            return docker(30, "version") == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private int docker(int timeoutSeconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
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
            throw new IOException("docker " + String.join(" ", arguments) + " timed out");
        }
        return process.exitValue();
    }
}
