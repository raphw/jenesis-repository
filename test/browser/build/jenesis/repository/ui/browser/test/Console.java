package build.jenesis.repository.ui.browser.test;

import module java.base;
import build.jenesis.repository.ui.Application;

/**
 * Boots the real repository console ({@link Application}) on an ephemeral port under the {@code dev} form-login
 * profile, over a throwaway filesystem store, and hands back the bound base URL - reusing {@link Application#start(int)}
 * so no Spring type leaks past this helper (the browser drives the assembled app over real HTTP). The console reads its
 * {@code ArtifactStore} backend from {@code JENESIS_STORE_ROOT} and its profile from {@code spring.profiles.active}, the
 * same system properties {@code ConsoleE2ETest} sets; the session cookie already defaults to non-secure
 * ({@code JENESIS_UI_SECURE_COOKIE:false} in {@code application.properties}), so the browser keeps {@code JSESSIONID}
 * over plain-HTTP loopback and form login sticks.
 */
final class Console implements AutoCloseable {

    private final Application.Running running;
    private final Path store;

    private Console(Application.Running running, Path store) {
        this.running = running;
        this.store = store;
    }

    static Console start() throws IOException {
        Path store = Files.createTempDirectory("jenesis-repository-console-browser");
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("spring.profiles.active", "dev");
        Application.Running running = Application.start(0);
        return new Console(running, store);
    }

    /** The base URL the browser navigates to - loopback so the host-networked container reaches it. */
    String baseUrl() {
        return "http://localhost:" + running.port();
    }

    /** The repository store root ({@code JENESIS_STORE_ROOT}), for the suite to seed artifacts into. */
    Path storeRoot() {
        return store;
    }

    @Override
    public void close() {
        try {
            running.close();
        } finally {
            System.clearProperty("JENESIS_STORE_ROOT");
            System.clearProperty("spring.profiles.active");
            deleteRecursively(store);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    // Best effort: a leaked temp store is under the OS temp dir and reclaimed by the OS.
                }
            });
        } catch (IOException _) {
            // Best effort.
        }
    }
}
