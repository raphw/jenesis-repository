package build.jenesis.repository.ui.browser.test;

import module java.base;
import module java.net.http;

/**
 * A throwaway {@code selenium/standalone-chrome} container for the browser suite, driven through the {@code docker}
 * CLI so the rig stays within {@code java.base}. It runs the node under <em>host networking</em> so the browser inside
 * the container reaches the ephemeral-port console the test boots on the host's loopback - the same address the test's
 * own HTTP would use - without a published-port or {@code host.docker.internal} dance. The WebDriver endpoint is
 * therefore always the host's {@code :4444}. Standard out and error are drained on separate background threads so an
 * image pull never deadlocks on a full pipe, and the container is force-removed on {@link #close()} (it also starts
 * with {@code --rm}).
 */
final class SeleniumContainer implements AutoCloseable {

    /** Pinned to the Selenium client version in this module's pins, so server and client speak the same protocol. */
    private static final String IMAGE = "selenium/standalone-chrome:4.35.0";
    private static final int PORT = 4444;

    private final String id;

    private SeleniumContainer(String id) {
        this.id = id;
    }

    /** Whether a Docker daemon is reachable; used to skip the suite where Docker is unavailable. */
    static boolean dockerAvailable() {
        try {
            return exec(20, "docker", "info").exit() == 0;
        } catch (IOException _) {
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Start the node detached under host networking, with a shared-memory bump so Chrome does not crash, and wait
     * until its {@code /status} reports ready. */
    static SeleniumContainer start() throws IOException, InterruptedException {
        Result result = exec(600, "docker", "run", "-d", "--rm",
                "--network", "host", "--shm-size", "2g", IMAGE);
        if (result.exit() != 0) {
            throw new IOException("`docker run " + IMAGE + "` failed (" + result.exit() + "): " + result.diagnostic());
        }
        SeleniumContainer container = new SeleniumContainer(result.stdout().strip());
        try {
            container.awaitReady(Duration.ofSeconds(120));
        } catch (IOException | InterruptedException | RuntimeException e) {
            container.close();
            throw e;
        }
        return container;
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
        throw new IOException("Selenium node did not become ready within " + timeout + "; last logs:\n" + logs(),
                last);
    }

    private String logs() {
        try {
            return exec(30, "docker", "logs", "--tail", "50", id).diagnostic();
        } catch (IOException e) {
            return "(no logs: " + e.getMessage() + ")";
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "(no logs: interrupted)";
        }
    }

    @Override
    public void close() {
        try {
            exec(60, "docker", "rm", "-f", id);
        } catch (IOException _) {
            // Best effort: the container is started with --rm, so it is removed on stop regardless.
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private record Result(int exit, String stdout, String stderr) {
        String diagnostic() {
            return stderr.isBlank() ? stdout : stderr;
        }
    }

    private static Result exec(int timeoutSeconds, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread drainOut = drain(process.getInputStream(), out);
        Thread drainErr = drain(process.getErrorStream(), err);
        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        drainOut.join(Duration.ofSeconds(10));
        drainErr.join(Duration.ofSeconds(10));
        if (!exited) {
            throw new IOException("Timed out after " + timeoutSeconds + "s: " + String.join(" ", command));
        }
        return new Result(process.exitValue(),
                out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Thread drain(InputStream stream, ByteArrayOutputStream sink) {
        return Thread.ofVirtual().start(() -> {
            try (stream) {
                stream.transferTo(sink);
            } catch (IOException _) {
                // The stream closes when the process exits; a read error here is not actionable.
            }
        });
    }
}
