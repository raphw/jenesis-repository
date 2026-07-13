package build.jenesis.repository.observation;

import module java.base;

/**
 * The seam a plugin reports its observability signals through: {@link #healthChecks()}, {@link #metrics()} and
 * {@link #taskStatuses()}, each defaulting to empty so a provider adopts only what it has - the same optional
 * default-method pattern its provider SPI already uses for {@code requiredConfig()}. A plugin (or its provider) implements
 * this and is discovered with {@link ServiceLoader}; a <em>disabled or absent</em> plugin contributes an empty source, or
 * none at all, so the overview never lists a signal for something that is not running.
 *
 * <p>The signals are self-describing (name + description) and registry-free: the distribution collects the sources into an
 * {@link ObservabilityReport} and bridges them onto Actuator and the console, so the plugin never touches Micrometer.
 */
public interface ObservabilitySource {

    /** The health checks this plugin reports; empty (the default) when it has none. */
    default List<HealthCheck> healthChecks() {
        return List.of();
    }

    /** The metrics this plugin reports; empty (the default) when it has none. */
    default List<Metric> metrics() {
        return List.of();
    }

    /** The background tasks this plugin reports the status of; empty (the default) when it runs none. */
    default List<TaskStatus> taskStatuses() {
        return List.of();
    }
}
