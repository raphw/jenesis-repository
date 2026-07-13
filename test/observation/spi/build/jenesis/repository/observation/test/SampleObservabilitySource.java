package build.jenesis.repository.observation.test;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;

import java.util.List;

/**
 * A {@code provides}-declared {@link ObservabilitySource} standing in for a real plugin, so the
 * {@link build.jenesis.repository.observation.ObservabilityReport#discover() ServiceLoader discovery} has something to
 * find. It reports one of each signal.
 */
public final class SampleObservabilitySource implements ObservabilitySource {

    @Override
    public List<HealthCheck> healthChecks() {
        return List.of(HealthCheck.of("jenesis.gc.worker", "The reclamation worker thread",
                Health.DEGRADED, "queue saturated"));
    }

    @Override
    public List<Metric> metrics() {
        return List.of(Metric.bounded("jenesis.quota.used.bytes", "Tenant quota consumed", 500, 1000, "bytes"));
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return List.of(TaskStatus.idle("jenesis.gc.sweep", "Periodic reclamation sweep"));
    }
}
