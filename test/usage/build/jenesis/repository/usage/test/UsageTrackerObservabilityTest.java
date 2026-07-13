package build.jenesis.repository.usage.test;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.usage.BatchingKeyUsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An enabled batching key-usage tracker is its own {@link build.jenesis.repository.observation.ObservabilitySource}:
 * it reports its bounded queue depth ({@code jenesis.usage.queue}, used vs the fixed capacity), the per-credential
 * accumulators it holds ({@code jenesis.usage.tracked}), the hits dropped under back-pressure
 * ({@code jenesis.usage.dropped}), a {@code jenesis.usage.worker} health check (DOWN when the worker died with
 * tracking on) and a {@code jenesis.usage.flush} task status stamped with the last drain; a disabled tracker reports
 * nothing at all. The signals collect into the single {@link ObservabilityReport} the distribution, Actuator and the
 * docs all read.
 */
class UsageTrackerObservabilityTest {

    @TempDir
    Path root;

    private Authorization authorization;
    private String hash;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
        hash = Authorization.hash(Authorization.mint("acme"));
        authorization.provision("acme", hash, "k", null);
    }

    @Test
    void a_disabled_tracker_reports_nothing() {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, false);

        assertThat(tracker.metrics()).as("tracking is off - nothing to report").isEmpty();
        assertThat(tracker.healthChecks()).isEmpty();
        assertThat(tracker.taskStatuses()).isEmpty();
        assertThat(ObservabilityReport.from(List.of(tracker)).overall()).isEqualTo(Health.UP);
    }

    @Test
    void an_enabled_but_unstarted_tracker_reports_a_down_worker_and_a_failed_flush() {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);

        assertThat(tracker.healthChecks()).singleElement().satisfies(check -> {
            assertThat(check.name()).isEqualTo("jenesis.usage.worker");
            assertThat(check.status()).as("switched on but its worker never started").isEqualTo(Health.DOWN);
            assertThat(check.detail()).isNotBlank();
        });
        assertThat(tracker.taskStatuses()).singleElement().satisfies(task -> {
            assertThat(task.name()).isEqualTo("jenesis.usage.flush");
            assertThat(task.state()).isEqualTo(TaskStatus.State.FAILED);
            assertThat(task.everRan()).as("no drain has happened yet").isFalse();
        });
    }

    @Test
    void a_started_tracker_reports_an_up_worker_and_a_running_flush() {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);
        tracker.start();
        try {
            assertThat(tracker.healthChecks()).singleElement()
                    .extracting(HealthCheck::status).isEqualTo(Health.UP);
            assertThat(tracker.taskStatuses()).singleElement()
                    .extracting(TaskStatus::state).isEqualTo(TaskStatus.State.RUNNING);
        } finally {
            tracker.close();
        }
        assertThat(tracker.healthChecks()).as("the worker is joined on close").singleElement()
                .extracting(HealthCheck::status).isEqualTo(Health.DOWN);
    }

    @Test
    void the_queue_metric_is_bounded_and_the_dropped_counter_climbs_past_capacity() {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);
        for (int index = 0; index < 100_100; index++) {
            tracker.record("acme", hash, null);   // no worker draining, so the queue fills and then drops
        }

        Metric queue = metric(tracker, "jenesis.usage.queue");
        assertThat(queue.kind()).isEqualTo(Metric.Kind.GAUGE);
        assertThat(queue.limit()).as("the fixed queue bound").hasValue(100_000.0);
        assertThat(queue.value()).as("filled to the bound").isEqualTo(100_000.0);
        assertThat(queue.usage()).as("used vs available, at the ceiling").hasValue(1.0);

        Metric dropped = metric(tracker, "jenesis.usage.dropped");
        assertThat(dropped.kind()).isEqualTo(Metric.Kind.COUNTER);
        assertThat(dropped.value()).as("offers past the bound are dropped").isGreaterThan(0.0);
    }

    @Test
    void a_drain_populates_the_tracked_gauge_and_stamps_the_flush_task() {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);
        Instant when = Instant.parse("2026-06-30T08:00:00Z");

        tracker.drain(List.of(new BatchingKeyUsageTracker.Hit("acme", hash, "10.0.0.1")), when);

        assertThat(metric(tracker, "jenesis.usage.tracked").value())
                .as("the drained credential is held pending its next-day flush").isEqualTo(1.0);
        assertThat(tracker.taskStatuses()).singleElement().satisfies(task -> {
            assertThat(task.everRan()).as("the drain stamped the task").isTrue();
            assertThat(task.lastRun()).isEqualTo(when);
        });
    }

    @Test
    void the_signals_collect_into_the_report_and_follow_the_feature_grammar() {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);

        ObservabilityReport report = ObservabilityReport.from(List.of(tracker));

        assertThat(report.metrics()).extracting(Metric::name)
                .containsExactly("jenesis.usage.dropped", "jenesis.usage.queue", "jenesis.usage.tracked");
        assertThat(report.metrics()).allSatisfy(metric ->
                assertThat(metric.name()).matches("jenesis\\.usage\\..+"));
        assertThat(report.metrics()).allSatisfy(metric -> assertThat(metric.description()).isNotBlank());
        assertThat(report.healthChecks()).extracting(HealthCheck::name).containsExactly("jenesis.usage.worker");
        assertThat(report.tasks()).extracting(TaskStatus::name).containsExactly("jenesis.usage.flush");
    }

    private static Metric metric(BatchingKeyUsageTracker tracker, String name) {
        return tracker.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name));
    }
}
