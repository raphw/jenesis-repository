package build.jenesis.repository.ui.browser.test;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriverException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link RetryOnBrowserTimeout} with no browser at all: it drives the retry loop directly with
 * synthetic failures. The contract under test is the whole point of the extension - a transient infra timeout is
 * retried within the run (the CI-minute saver), while a genuine product {@link AssertionError} fails fast on the
 * first attempt and is never retried (a real regression must not be masked).
 */
class RetryOnBrowserTimeoutTest {

    @Test
    void a_transient_browser_timeout_is_retried_until_it_passes() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        RetryOnBrowserTimeout.retry("synthetic", () -> {
            if (calls.incrementAndGet() < RetryOnBrowserTimeout.MAX_ATTEMPTS) {
                throw new BrowserTimeoutException("Timed out after PT20S waiting for: a displayed element");
            }
            // the final attempt passes
        });
        assertThat(calls.get())
                .as("ran once then retried until it passed, within the same run")
                .isEqualTo(RetryOnBrowserTimeout.MAX_ATTEMPTS);
    }

    @Test
    void selenium_timeout_and_webdriver_exceptions_count_as_transient_infra() {
        assertThat(RetryOnBrowserTimeout.isTransientInfrastructure(new BrowserTimeoutException("wait budget"))).isTrue();
        assertThat(RetryOnBrowserTimeout.isTransientInfrastructure(new TimeoutException("grid slow under load"))).isTrue();
        assertThat(RetryOnBrowserTimeout.isTransientInfrastructure(new WebDriverException("session evicted"))).isTrue();
        // A cause-chain match still counts (a wait wraps the last stale/no-such-element read).
        assertThat(RetryOnBrowserTimeout.isTransientInfrastructure(
                new BrowserTimeoutException("timed out", new WebDriverException("stale")))).isTrue();
    }

    @Test
    void a_genuine_assertion_failure_is_not_retried_and_fails_fast() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() ->
                RetryOnBrowserTimeout.retry("synthetic", () -> {
                    calls.incrementAndGet();
                    throw new AssertionError("child rows were swapped in, not navigated to");
                }))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("child rows were swapped in");
        assertThat(calls.get())
                .as("a product assertion is attempted exactly once - never retried")
                .isEqualTo(1);
    }

    @Test
    void a_plain_assertion_error_is_not_classified_as_transient_infra() {
        assertThat(RetryOnBrowserTimeout.isTransientInfrastructure(new AssertionError("real regression"))).isFalse();
    }

    @Test
    void an_always_timing_out_spec_surfaces_its_failure_after_exhausting_retries() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() ->
                RetryOnBrowserTimeout.retry("synthetic", () -> {
                    calls.incrementAndGet();
                    throw new BrowserTimeoutException("always times out");
                }))
                .isInstanceOf(BrowserTimeoutException.class);
        assertThat(calls.get())
                .as("tried the maximum number of attempts, then surfaced the failure")
                .isEqualTo(RetryOnBrowserTimeout.MAX_ATTEMPTS);
    }
}
