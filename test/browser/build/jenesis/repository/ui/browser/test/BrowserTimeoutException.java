package build.jenesis.repository.ui.browser.test;

/**
 * Thrown by {@link ConsoleBrowser}'s wait helpers when a poll exhausts its budget - a DISTINCT type from a product
 * {@link AssertionError}, so the {@link RetryOnBrowserTimeout} extension can re-run a spec that starved on infra (the
 * Selenium/Chrome container + console overloaded under CI load), while a genuine assertion failure keeps its
 * {@code AssertionError} type, fails fast on the first attempt and is never retried.
 */
final class BrowserTimeoutException extends RuntimeException {

    BrowserTimeoutException(String message) {
        super(message);
    }

    BrowserTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
