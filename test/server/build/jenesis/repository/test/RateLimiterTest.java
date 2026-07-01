package build.jenesis.repository.test;

import build.jenesis.repository.server.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token bucket allows a window's worth of burst, refuses once drained, refills at the configured rate as the
 * clock advances, keys buckets independently, and treats a non-positive rate as unlimited.
 */
class RateLimiterTest {

    @Test
    void a_bucket_allows_a_burst_then_refills_as_the_clock_advances() {
        AtomicLong nanos = new AtomicLong();
        RateLimiter limiter = new RateLimiter().withClock(nanos::get);

        for (int permit = 0; permit < 60; permit++) {
            assertThat(limiter.allow("acme", 60)).as("burst permit " + permit).isTrue();
        }
        assertThat(limiter.allow("acme", 60)).as("burst drained").isFalse();

        nanos.addAndGet(1_000_000_000L);
        assertThat(limiter.allow("acme", 60)).as("one second refills one permit").isTrue();
        assertThat(limiter.allow("acme", 60)).as("and only one").isFalse();
    }

    @Test
    void keys_are_independent_and_a_non_positive_rate_is_unlimited() {
        AtomicLong nanos = new AtomicLong();
        RateLimiter limiter = new RateLimiter().withClock(nanos::get);

        for (int permit = 0; permit < 60; permit++) {
            limiter.allow("acme", 60);
        }
        assertThat(limiter.allow("acme", 60)).as("acme drained").isFalse();
        assertThat(limiter.allow("globex", 60)).as("another key has its own bucket").isTrue();

        for (int permit = 0; permit < 1000; permit++) {
            assertThat(limiter.allow("acme", 0)).as("zero is unlimited").isTrue();
        }
    }
}
