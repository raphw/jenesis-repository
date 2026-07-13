package build.jenesis.repository.observation.test;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single collected view: {@link ObservabilityReport#from} merges and name-sorts the signals of every source,
 * {@link ObservabilityReport#overall} collapses the health checks to the worst state, a source that reports nothing
 * contributes nothing (graceful degradation), and {@link ObservabilityReport#discover} finds the
 * {@code provides}-declared {@link SampleObservabilitySource} through {@link java.util.ServiceLoader}.
 */
class ObservabilityReportTest {

    private static ObservabilitySource source(HealthCheck health, Metric metric, TaskStatus task) {
        return new ObservabilitySource() {
            @Override
            public List<HealthCheck> healthChecks() {
                return List.of(health);
            }

            @Override
            public List<Metric> metrics() {
                return List.of(metric);
            }

            @Override
            public List<TaskStatus> taskStatuses() {
                return List.of(task);
            }
        };
    }

    @Test
    void merges_and_name_sorts_the_signals_of_every_source() {
        ObservabilityReport report = ObservabilityReport.from(List.of(
                source(HealthCheck.up("jenesis.zeta.check", "z"),
                        Metric.gauge("jenesis.zeta.gauge", "z", 1, ""),
                        TaskStatus.idle("jenesis.zeta.task", "z")),
                source(HealthCheck.up("jenesis.alpha.check", "a"),
                        Metric.gauge("jenesis.alpha.gauge", "a", 1, ""),
                        TaskStatus.idle("jenesis.alpha.task", "a"))));

        assertThat(report.healthChecks()).extracting(HealthCheck::name)
                .containsExactly("jenesis.alpha.check", "jenesis.zeta.check");
        assertThat(report.metrics()).extracting(Metric::name)
                .containsExactly("jenesis.alpha.gauge", "jenesis.zeta.gauge");
        assertThat(report.tasks()).extracting(TaskStatus::name)
                .containsExactly("jenesis.alpha.task", "jenesis.zeta.task");
    }

    @Test
    void overall_health_is_the_worst_across_all_checks() {
        ObservabilityReport clean = ObservabilityReport.from(List.of(
                source(HealthCheck.up("jenesis.a.check", "a"),
                        Metric.gauge("jenesis.a.g", "a", 1, ""), TaskStatus.idle("jenesis.a.t", "a"))));
        assertThat(clean.overall()).isEqualTo(Health.UP);

        ObservabilityReport mixed = ObservabilityReport.from(List.of(
                source(HealthCheck.up("jenesis.a.check", "a"),
                        Metric.gauge("jenesis.a.g", "a", 1, ""), TaskStatus.idle("jenesis.a.t", "a")),
                source(HealthCheck.of("jenesis.b.check", "b", Health.DOWN, "dead"),
                        Metric.gauge("jenesis.b.g", "b", 1, ""), TaskStatus.idle("jenesis.b.t", "b"))));
        assertThat(mixed.overall()).isEqualTo(Health.DOWN);
    }

    @Test
    void an_empty_source_set_is_healthy_and_carries_nothing() {
        ObservabilityReport empty = ObservabilityReport.from(List.of());
        assertThat(empty.healthChecks()).isEmpty();
        assertThat(empty.metrics()).isEmpty();
        assertThat(empty.tasks()).isEmpty();
        assertThat(empty.overall()).isEqualTo(Health.UP);
    }

    @Test
    void a_source_reporting_nothing_degrades_gracefully() {
        ObservabilityReport report = ObservabilityReport.from(List.of(new ObservabilitySource() {
        }));
        assertThat(report.healthChecks()).isEmpty();
        assertThat(report.metrics()).isEmpty();
        assertThat(report.tasks()).isEmpty();
    }

    @Test
    void discovers_the_service_loader_installed_source() {
        ObservabilityReport report = ObservabilityReport.discover();
        assertThat(report.healthChecks()).extracting(HealthCheck::name).contains("jenesis.gc.worker");
        assertThat(report.metrics()).extracting(Metric::name).contains("jenesis.quota.used.bytes");
        assertThat(report.tasks()).extracting(TaskStatus::name).contains("jenesis.gc.sweep");
        assertThat(report.overall()).isEqualTo(Health.DEGRADED);
    }
}
