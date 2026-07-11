package build.jenesis.repository.store.gcs.test;

import org.junit.jupiter.api.Assumptions;

/**
 * Gates a test on its environment - a tool on the {@code PATH}, a reachable upstream, a running daemon. By default an
 * unmet requirement skips the test (a JUnit assumption), so a developer machine without every toolchain stays green.
 * When the test JVM carries {@code -Djenesis.test.required} (injected by the {@code ci} profile's process-override
 * properties), an unmet requirement fails the test instead: CI installs every tool the selected suites need, so a
 * skip there is a broken lane hiding as green, never an acceptable outcome.
 */
final class Requirement {

    private Requirement() {
    }

    /** Skip the test when {@code satisfied} is false - or fail it where the environment is declared complete. */
    static void requireOrSkip(boolean satisfied, String reason) {
        String required = System.getProperty("jenesis.test.required");
        if (!satisfied && required != null && !required.equalsIgnoreCase("false")) {
            throw new AssertionError(reason
                    + " - and jenesis.test.required declares this environment complete, so skipping would hide a broken lane");
        }
        Assumptions.assumeTrue(satisfied, reason);
    }
}
