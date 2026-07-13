/**
 * The observability SPI: the self-describing signals a plugin reports about itself - {@link
 * build.jenesis.repository.observation.HealthCheck health checks}, {@link build.jenesis.repository.observation.Metric
 * metrics} and {@link build.jenesis.repository.observation.TaskStatus background-task status} - each carrying a stable
 * {@code jenesis.<feature>.<signal>} name (the {@link build.jenesis.repository.observation.Signals} grammar, the same
 * {@code jenesis.<feature>.*} convention configuration uses) and a human-readable description. A plugin exposes them by
 * implementing {@link build.jenesis.repository.observation.ObservabilitySource} through the optional default-method
 * pattern its provider SPI already uses for {@code requiredConfig()}; it is discovered with {@link
 * java.util.ServiceLoader}, and a <em>disabled or absent</em> plugin contributes nothing - so the overview never lists
 * a signal for something that is not running.
 *
 * <p>The module is deliberately registry-free and {@code java.base}-only, so every format / compliance / storage /
 * maintenance SPI can {@code requires} it without dragging in Micrometer or Spring. The distribution collects the
 * sources into one {@link build.jenesis.repository.observation.ObservabilityReport} and bridges it onto Actuator
 * ({@code /actuator/health}, {@code /actuator/metrics}), the console overview page and the reference docs - one source
 * of truth (name + description), three consumers - while the plugin itself never touches a meter registry.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.observation {
    exports build.jenesis.repository.observation;
    uses build.jenesis.repository.observation.ObservabilitySource;
}
