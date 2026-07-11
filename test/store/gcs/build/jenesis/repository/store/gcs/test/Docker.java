package build.jenesis.repository.store.gcs.test;

import module java.base;

/**
 * A throwaway Docker container for an integration test, driven through the {@code docker} CLI so the
 * test stays within {@code java.base} (Testcontainers cannot be a module dependency in this build). It
 * starts a detached container with one published port, hands back the ephemeral host port it landed
 * on, and force-removes the container on {@link #close()}. Standard out and error are drained on
 * separate background threads, so an image pull (whose progress goes to stderr) never deadlocks on a
 * full pipe and never pollutes the container id read from stdout.
 */
final class Docker implements AutoCloseable {

    private final String id;

    private Docker(String id) {
        this.id = id;
    }

    /** Whether a Docker daemon is reachable; used to skip the suite where Docker is unavailable. */
    static boolean available() {
        try {
            return exec(20, "docker", "info").exit() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Start {@code image} detached with {@code command}, publishing {@code containerPort} to an ephemeral host port. */
    static Docker start(String image, int containerPort, String... command) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(List.of(
                "docker", "run", "-d", "--rm", "-p", Integer.toString(containerPort), image));
        Collections.addAll(argv, command);
        Result result = exec(600, argv.toArray(String[]::new));
        if (result.exit() != 0) {
            throw new IOException("`docker run` failed (" + result.exit() + "): " + result.diagnostic());
        }
        return new Docker(result.stdout().strip());
    }

    /** The ephemeral host port that {@code containerPort} was published to. */
    int hostPort(int containerPort) throws IOException, InterruptedException {
        Result result = exec(30, "docker", "port", id, Integer.toString(containerPort));
        if (result.exit() != 0) {
            throw new IOException("`docker port` failed (" + result.exit() + "): " + result.diagnostic());
        }
        String line = result.stdout().lines().findFirst().orElseThrow(
                () -> new IOException("No published port for " + containerPort + " on " + id));
        return Integer.parseInt(line.substring(line.lastIndexOf(':') + 1).strip());
    }

    @Override
    public void close() {
        try {
            exec(60, "docker", "rm", "-f", id);
        } catch (IOException e) {
            // Best effort: the container is started with --rm, so it is removed on stop regardless.
        } catch (InterruptedException e) {
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
            } catch (IOException ignored) {
                // The stream closes when the process exits; a read error here is not actionable.
            }
        });
    }
}
