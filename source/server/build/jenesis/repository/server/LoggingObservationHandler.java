package build.jenesis.repository.server;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The logging pillar of the Observation API, in one place beside {@link Observations}: it logs each observed
 * operation once it completes, with the observation name, its key-values (repository, tenant, any outcome) and any
 * error. Registered once as a bean by the free auto-configuration, so a single handler lights logging for every
 * {@code jenesis.*} operation wherever the server module runs - the console, the maintenance sweep, the enterprise
 * controllers - instead of each module carrying its own copy. Boot's observation auto-configuration attaches it to
 * the auto-configured {@code ObservationRegistry} alongside the metrics handler (Micrometer, exposed through
 * Actuator) and a tracing handler (when a tracing bridge is on the path), so one instrumentation point feeds
 * logging, metrics and tracing; when tracing is on the log line carries the trace and span ids automatically.
 */
public final class LoggingObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Logger LOGGER = LoggerFactory.getLogger("build.jenesis.observation");

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }

    @Override
    public void onStop(Observation.Context context) {
        if (context.getError() == null) {
            LOGGER.info("{} {}", context.getName(), context.getAllKeyValues());
        } else {
            LOGGER.warn("{} {} failed: {}", context.getName(), context.getAllKeyValues(),
                    context.getError().toString());
        }
    }
}
