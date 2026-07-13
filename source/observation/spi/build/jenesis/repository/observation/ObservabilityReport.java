package build.jenesis.repository.observation;

import module java.base;

/**
 * The single collected view every consumer reads - the console overview page, the Actuator health/metrics contributors
 * and the reference docs all render <em>this</em>, so a signal is named and described in exactly one place. {@link #from}
 * merges the signals of a set of {@link ObservabilitySource}s (name-sorted for a stable ordering); {@link #discover} does
 * the same over the {@link ServiceLoader}-installed sources; {@link #overall} collapses the health checks into one
 * verdict. A source that reports nothing (a disabled plugin) simply adds nothing - the report degrades gracefully to
 * whatever is actually running.
 */
public record ObservabilityReport(List<HealthCheck> healthChecks, List<Metric> metrics, List<TaskStatus> tasks) {

    public ObservabilityReport {
        healthChecks = List.copyOf(healthChecks);
        metrics = List.copyOf(metrics);
        tasks = List.copyOf(tasks);
    }

    /** Collect and name-sort the signals of {@code sources}. */
    public static ObservabilityReport from(Iterable<? extends ObservabilitySource> sources) {
        List<HealthCheck> health = new ArrayList<>();
        List<Metric> metrics = new ArrayList<>();
        List<TaskStatus> tasks = new ArrayList<>();
        for (ObservabilitySource source : sources) {
            health.addAll(source.healthChecks());
            metrics.addAll(source.metrics());
            tasks.addAll(source.taskStatuses());
        }
        health.sort(Comparator.comparing(HealthCheck::name));
        metrics.sort(Comparator.comparing(Metric::name));
        tasks.sort(Comparator.comparing(TaskStatus::name));
        return new ObservabilityReport(health, metrics, tasks);
    }

    /** Collect the signals of every {@link ServiceLoader}-discovered {@link ObservabilitySource}. */
    public static ObservabilityReport discover() {
        return from(ServiceLoader.load(ObservabilitySource.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    /** The worst health across every check - {@link Health#UP} when nothing reports trouble. */
    public Health overall() {
        Health overall = Health.UP;
        for (HealthCheck check : healthChecks) {
            overall = overall.worst(check.status());
        }
        return overall;
    }
}
