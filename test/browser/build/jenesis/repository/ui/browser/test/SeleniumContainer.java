package build.jenesis.repository.ui.browser.test;

import module java.base;
import module java.net.http;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A throwaway {@code selenium/standalone-chrome} node for the browser suite, brought up as a Testcontainers
 * {@link GenericContainer} rather than a hand-rolled {@code docker} CLI. It runs under <em>host networking</em>
 * ({@link GenericContainer#withNetworkMode(String) withNetworkMode("host")}) on purpose: the browser inside the
 * container must reach the ephemeral-port console the test boots on the host's loopback at the same address the test's
 * own HTTP uses, without a published-port or {@code host.docker.internal} dance. Bridged networking would need the
 * in-JVM console and the containerised browser to share a hostname neither can (the console cannot resolve
 * {@code host.testcontainers.internal}, a bridged browser's {@code localhost} is not the host's), so host networking is
 * the mechanism that keeps a single loopback identity working for both. The WebDriver endpoint is therefore the host's
 * {@code :4444} and there are no published-port mappings; readiness is polled off {@code /status}. Force-removed on
 * {@link #close()}.
 */
final class SeleniumContainer implements AutoCloseable {

    /** Pinned to the Selenium client version in this module's pins, so server and client speak the same protocol. */
    private static final String IMAGE = "selenium/standalone-chrome:4.35.0@sha256:be55620222a49b5ed58787573c6ae864ed86833668b5a54a792f5419befb1dfd";
    private static final int PORT = 4444;
    private static final long SHARED_MEMORY = 2L * 1024 * 1024 * 1024;

    private final GenericContainer<?> container;

    private SeleniumContainer(GenericContainer<?> container) {
        this.container = container;
    }

    /** Whether a Docker daemon is reachable; used to skip the suite where Docker is unavailable. */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Start the node under host networking, with a shared-memory bump so Chrome does not crash, and wait until its
     *  {@code /status} reports ready. */
    static SeleniumContainer start() throws IOException, InterruptedException {
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withNetworkMode("host")
                .withSharedMemorySize(SHARED_MEMORY)
                // Host networking publishes no ports, so a mapped-port wait cannot apply; gate the container boot on
                // the supervisor bringing the node process up, then poll /status for true grid readiness below.
                .waitingFor(Wait.forLogMessage(".*selenium-standalone entered RUNNING state.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));
        container.start();
        SeleniumContainer selenium = new SeleniumContainer(container);
        try {
            selenium.awaitReady(Duration.ofSeconds(120));
        } catch (IOException | InterruptedException | RuntimeException notReady) {
            selenium.close();
            throw notReady;
        }
        return selenium;
    }

    /** The WebDriver remote endpoint - the host's {@code :4444} under host networking. */
    URI webDriverUrl() {
        return URI.create("http://localhost:" + PORT + "/");
    }

    private void awaitReady(Duration timeout) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/status"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        Instant deadline = Instant.now().plus(timeout);
        IOException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"ready\": true")) {
                    return;
                }
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(500);
        }
        throw new IOException("Selenium node did not become ready within " + timeout + "; last logs:\n"
                + container.getLogs(), last);
    }

    @Override
    public void close() {
        container.stop();
    }
}
