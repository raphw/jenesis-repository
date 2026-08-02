package build.jenesis.repository.test;

import module java.base;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs an ecosystem client (Maven, npm, cargo, ...) from a pinned Docker image instead of a tool the developer had to
 * install on the host {@code PATH}. The container joins <em>host networking</em>
 * ({@link GenericContainer#withNetworkMode(String) withNetworkMode("host")}) so the client reaches the in-JVM
 * repository the test boots on the host's ephemeral loopback port at the exact {@code localhost:<port>} the test's own
 * HTTP uses - the same single-loopback-identity reason the browser suite runs host-networked. A host directory (the
 * test's {@code @TempDir}) is bind-mounted at {@code /work} so artifacts staged by the test are visible to the client
 * and files the client resolves are visible back to the test's assertions.
 *
 * <p>The container is kept alive on a {@code sleep} and each client invocation is an {@link #exec exec} into it, so a
 * test can run several commands (deploy then resolve) against one booted client.
 *
 * <p><strong>Proxy / TLS pass-through.</strong> Only when the host environment actually carries them, the proxy and CA
 * settings this sandbox uses are forwarded into the container: {@code HTTPS_PROXY}/{@code HTTP_PROXY}/{@code NO_PROXY},
 * the JVM {@code JAVA_TOOL_OPTIONS} (with the referenced PKCS12 truststore bind-mounted at its own path, so a JVM tool
 * like Maven trusts the intercepting proxy and honours its {@code nonProxyHosts}), and the CA bundle wired into the
 * common non-JVM tool variables ({@code NODE_EXTRA_CA_CERTS}, {@code REQUESTS_CA_BUNDLE}, {@code CARGO_HTTP_CAINFO},
 * ...). In an ordinary CI environment none of these are set, so nothing is forwarded and the client reaches its
 * registries directly - the migration adds no dependency on this sandbox's proxy.
 */
final class ToolContainer implements AutoCloseable {

    private static final String CA_IN_CONTAINER = "/etc/jenesis/proxy-ca.crt";

    private final GenericContainer<?> container;

    private ToolContainer(GenericContainer<?> container) {
        this.container = container;
    }

    /** Whether a Docker daemon is reachable; used to skip a suite where Docker is unavailable. */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Boot {@code image} host-networked with {@code work} bind-mounted read-write at {@code /work}, kept alive for
     *  {@link #exec}. Proxy/CA settings are forwarded only where the host environment provides them (see class doc). */
    static ToolContainer start(String image, Path work) {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                .withNetworkMode("host")
                .withFileSystemBind(work.toAbsolutePath().toString(), "/work", BindMode.READ_WRITE)
                .withWorkingDirectory("/work")
                // Host networking exposes no ports and the keep-alive command emits no service log of its own, so gate
                // readiness on a marker line the command prints before it parks on sleep.
                .withCommand("sh", "-c", "echo TOOLCONTAINER_READY; exec sleep infinity")
                .waitingFor(Wait.forLogMessage(".*TOOLCONTAINER_READY.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));
        forwardProxyAndCa(container);
        container.start();
        return new ToolContainer(container);
    }

    /** Run {@code command} in the client container, returning its exit code and combined stdout+stderr. */
    Result exec(Duration timeout, String... command) throws IOException, InterruptedException {
        Callable<Container.ExecResult> call = () -> container.execInContainer(command);
        FutureTask<Container.ExecResult> task = new FutureTask<>(call);
        Thread worker = Thread.ofVirtual().start(task);
        try {
            Container.ExecResult result = task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Result(result.getExitCode(), result.getStdout() + result.getStderr());
        } catch (TimeoutException timedOut) {
            worker.interrupt();
            throw new IOException(String.join(" ", command) + " timed out after " + timeout);
        } catch (ExecutionException failed) {
            throw new IOException(String.join(" ", command) + " failed", failed.getCause());
        }
    }

    /** The exit code and combined output of one {@link #exec} call. */
    record Result(int exitCode, String output) {
    }

    /** The client's view of the host repository under host networking: the host's own loopback and port. */
    static String repositoryBase(int port) {
        return "http://localhost:" + port + "/repository/";
    }

    @Override
    public void close() {
        container.stop();
    }

    private static void forwardProxyAndCa(GenericContainer<?> container) {
        forwardEnv(container, "HTTPS_PROXY");
        forwardEnv(container, "HTTP_PROXY");
        forwardEnv(container, "NO_PROXY");

        // A JVM tool (Maven) honours JAVA_TOOL_OPTIONS automatically; forward it and bind-mount the PKCS12 truststore
        // it names at the same path so the intercepting proxy's CA is trusted inside the container too.
        String javaToolOptions = System.getenv("JAVA_TOOL_OPTIONS");
        if (javaToolOptions != null && !javaToolOptions.isBlank()) {
            container.withEnv("JAVA_TOOL_OPTIONS", javaToolOptions);
            Matcher trustStore = Pattern.compile("-Djavax\\.net\\.ssl\\.trustStore=(\\S+)").matcher(javaToolOptions);
            if (trustStore.find()) {
                Path store = Path.of(trustStore.group(1));
                if (Files.exists(store)) {
                    container.withFileSystemBind(store.toAbsolutePath().toString(), store.toString(),
                            BindMode.READ_ONLY);
                }
            }
        }

        // Non-JVM clients read the CA from tool-specific variables; wire them all to one mounted bundle where the host
        // provides it. Registries this sandbox reaches directly (in NO_PROXY) still verify against the real chain.
        Path caBundle = caBundlePath();
        if (caBundle != null) {
            container.withFileSystemBind(caBundle.toString(), CA_IN_CONTAINER, BindMode.READ_ONLY);
            for (String variable : List.of("NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE", "PIP_CERT", "CURL_CA_BUNDLE",
                    "SSL_CERT_FILE", "CARGO_HTTP_CAINFO", "GIT_SSL_CAINFO")) {
                container.withEnv(variable, CA_IN_CONTAINER);
            }
        }
    }

    private static void forwardEnv(GenericContainer<?> container, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            container.withEnv(name, value);
        }
    }

    /** The host proxy's CA bundle, if this environment has one to trust (otherwise there is nothing to forward). */
    private static Path caBundlePath() {
        String explicit = System.getenv("NODE_EXTRA_CA_CERTS");
        if (explicit != null && !explicit.isBlank() && Files.exists(Path.of(explicit))) {
            return Path.of(explicit);
        }
        Path standard = Path.of("/root/.ccr/ca-bundle.crt");
        return Files.exists(standard) ? standard : null;
    }
}
