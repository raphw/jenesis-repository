package build.jenesis.repository.observation.test;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three self-describing signal descriptors: each carries a validated name and a description, null-guards its
 * optional fields, and a {@link Metric} with a limit yields the used-vs-available fraction the console renders.
 */
class SignalDescriptorTest {

    @Test
    void health_severity_collapses_to_the_worst() {
        assertThat(Health.UP.worst(Health.UNKNOWN)).isEqualTo(Health.UNKNOWN);
        assertThat(Health.UNKNOWN.worst(Health.DEGRADED)).isEqualTo(Health.DEGRADED);
        assertThat(Health.DEGRADED.worst(Health.DOWN)).isEqualTo(Health.DOWN);
        assertThat(Health.DOWN.worst(Health.UP)).isEqualTo(Health.DOWN);
        assertThat(Health.UP.worst(Health.UP)).isEqualTo(Health.UP);
    }

    @Test
    void health_check_defaults_a_null_detail_to_empty() {
        HealthCheck up = HealthCheck.up("jenesis.gc.worker", "The reclamation worker thread");
        assertThat(up.status()).isEqualTo(Health.UP);
        assertThat(up.detail()).isEmpty();

        HealthCheck down = HealthCheck.of("jenesis.gc.worker", "The reclamation worker thread", Health.DOWN, null);
        assertThat(down.detail()).isEmpty();
    }

    @Test
    void a_descriptor_validates_its_name() {
        assertThatThrownBy(() -> HealthCheck.up("gc.worker", "no jenesis root"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Metric.gauge("jenesis_gc_bytes", "snake case", 1, "bytes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskStatus.idle("jenesis.GC", "uppercase"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_counter_and_a_gauge_carry_no_limit() {
        Metric counter = Metric.counter("jenesis.cache.requests", "Cache requests served", 42, "");
        assertThat(counter.kind()).isEqualTo(Metric.Kind.COUNTER);
        assertThat(counter.limit()).isEmpty();
        assertThat(counter.usage()).isEmpty();

        Metric gauge = Metric.gauge("jenesis.search.documents", "Indexed documents", 7, "");
        assertThat(gauge.kind()).isEqualTo(Metric.Kind.GAUGE);
        assertThat(gauge.usage()).isEmpty();
    }

    @Test
    void a_bounded_metric_reports_used_vs_available() {
        Metric quota = Metric.bounded("jenesis.quota.used.bytes", "Tenant quota consumed", 750, 1000, "bytes");
        assertThat(quota.kind()).isEqualTo(Metric.Kind.GAUGE);
        assertThat(quota.limit()).hasValue(1000);
        assertThat(quota.usage()).hasValue(0.75);
    }

    @Test
    void a_non_positive_limit_yields_no_usage_fraction() {
        Metric empty = new Metric("jenesis.quota.used.bytes", "Empty quota", Metric.Kind.GAUGE, 0,
                OptionalDouble.of(0), "bytes");
        assertThat(empty.usage()).isEmpty();
    }

    @Test
    void a_task_status_tracks_last_run_and_defaults_a_null_outcome() {
        TaskStatus idle = TaskStatus.idle("jenesis.gc.sweep", "Periodic reclamation sweep");
        assertThat(idle.everRan()).isFalse();
        assertThat(idle.outcome()).isEmpty();
        assertThat(idle.lastRun()).isNull();

        Instant when = Instant.parse("2026-07-13T00:00:00Z");
        TaskStatus ran = TaskStatus.ran("jenesis.gc.sweep", "Periodic reclamation sweep",
                TaskStatus.State.IDLE, when, Duration.ofSeconds(3), "reclaimed 12 blobs");
        assertThat(ran.everRan()).isTrue();
        assertThat(ran.lastRun()).isEqualTo(when);
        assertThat(ran.lastDuration()).isEqualTo(Duration.ofSeconds(3));
        assertThat(ran.outcome()).isEqualTo("reclaimed 12 blobs");
    }
}
