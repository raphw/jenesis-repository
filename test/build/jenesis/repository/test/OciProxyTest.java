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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the {@link build.jenesis.repository.format.oci.OciFormat} pull-through adapter against the real Docker Hub: a
 * {@link RepositoryApplication} configured to proxy {@code /v2/} misses to {@code registry-1.docker.io} serves a real
 * public image ({@code library/hello-world}, multi-arch) that was never pushed locally, with the real {@code docker}
 * client - exercising the Distribution bearer-token flow, {@code Accept} negotiation and the manifest-index then
 * per-architecture manifest and blob fetches. Tagged {@code docker}; self-skips without Docker or Docker Hub.
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OciProxyTest {

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private String registry;
    private String lastOutput = "";

    @BeforeAll
    public void start() {
        assumeTrue(dockerAvailable(), "Docker is required for the OCI proxy integration test");
        assumeTrue(reachable("registry-1.docker.io", 443), "Docker Hub must be reachable");
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        running = RepositoryApplication.start(0, Map.of("oci", URI.create("https://registry-1.docker.io/")));
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
    public void a_public_image_is_pulled_through_the_proxy() throws Exception {
        String reference = registry + "/library/hello-world:latest";
        docker(60, "image", "rm", "-f", reference);

        assertThat(docker(180, "pull", reference)).as("pull through the proxy: " + lastOutput).isZero();
        assertThat(docker(30, "image", "inspect", reference))
                .as("the proxied image is present locally: " + lastOutput).isZero();

        docker(60, "image", "rm", "-f", reference);
    }

    private boolean dockerAvailable() {
        try {
            return docker(30, "version") == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (IOException e) {
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
            throw new IOException("docker " + String.join(" ", arguments) + " timed out");
        }
        return process.exitValue();
    }
}
