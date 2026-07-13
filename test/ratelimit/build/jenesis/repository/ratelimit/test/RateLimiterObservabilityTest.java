package build.jenesis.repository.ratelimit.test;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.ratelimit.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter is its own {@link build.jenesis.repository.observation.ObservabilitySource}: it reports the number of
 * keys it is currently tracking as the {@code jenesis.ratelimit.buckets} gauge (one bucket per active key - the
 * memory-exhaustion vector the shared {@code anonymous} bucket bounds), a {@code jenesis.ratelimit.limiter} health
 * check, and no background task; the signals collect into the single {@link ObservabilityReport} view the
 * distribution, Actuator and the docs all read.
 */
class RateLimiterObservabilityTest {

    @Test
    void a_fresh_limiter_tracks_no_buckets() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();

        assertThat(limiter.metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.name()).isEqualTo("jenesis.ratelimit.buckets");
            assertThat(metric.kind()).isEqualTo(Metric.Kind.GAUGE);
            assertThat(metric.value()).isZero();
            assertThat(metric.limit()).isEmpty();
        });
        assertThat(limiter.taskStatuses()).isEmpty();
    }

    @Test
    void the_bucket_gauge_counts_only_the_distinct_rate_limited_keys() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();

        limiter.allow("acme", 60);
        limiter.allow("acme", 60);   // same key: still one bucket
        limiter.allow("globex", 60);
        limiter.allow("initech", 0); // unlimited: never mints a bucket

        assertThat(limiter.metrics()).singleElement()
                .extracting(Metric::value).isEqualTo(2.0);
    }

    @Test
    void the_signals_collect_into_the_report_the_consumers_read() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
        limiter.allow("acme", 60);

        ObservabilityReport report = ObservabilityReport.from(List.of(limiter));

        assertThat(report.metrics()).extracting(Metric::name).containsExactly("jenesis.ratelimit.buckets");
        assertThat(report.metrics()).first().extracting(Metric::value).isEqualTo(1.0);
        assertThat(report.healthChecks()).singleElement().satisfies(check -> {
            assertThat(check.name()).isEqualTo("jenesis.ratelimit.limiter");
            assertThat(check.status()).isEqualTo(Health.UP);
            assertThat(check.description()).isNotBlank();
        });
        assertThat(report.tasks()).isEmpty();
        assertThat(report.overall()).isEqualTo(Health.UP);
    }

    @Test
    void every_signal_name_follows_the_jenesis_feature_grammar() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
        limiter.allow("acme", 60);

        assertThat(limiter.metrics()).extracting(Metric::name)
                .allSatisfy(name -> assertThat(name).matches("jenesis\\.ratelimit\\..+"));
        assertThat(limiter.healthChecks()).extracting(HealthCheck::name)
                .allSatisfy(name -> assertThat(name).matches("jenesis\\.ratelimit\\..+"));
    }
}
