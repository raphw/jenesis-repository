package build.jenesis.repository.server;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.io.IOException;

/**
 * The one place the Observation API is opened, so every observed <em>operation</em> in the product wraps the same
 * way: a single instrumentation point that Boot's observation support fans out to a timer, the logging handler and
 * (when a tracing bridge is on the path) a span. It lives beside the serving seams in the free core - the module
 * that already {@code requires micrometer.observation} - so the console module, the maintenance module and the
 * enterprise controllers all collapse their private copies onto it rather than each re-deriving the choreography.
 *
 * <p>The repository and tenant ride as high-cardinality key-values (kept off the metric tags, so they never explode
 * the meter cardinality) and both are null-guarded to {@code "none"} here - a deployment-wide operation carries no
 * repository, and an operation raised before a tenant is resolved carries no tenant, and neither must NPE the
 * instrumentation. The call adds any low-cardinality outcome tag (a deploy verdict, an import source, a console
 * action) to the {@link Observation} it is handed, which does become a metric tag. Naming follows the
 * {@code jenesis.<area>.<signal>} convention documented in {@code OBSERVABILITY.md}.
 */
public final class Observations {

    private Observations() {
    }

    /**
     * Run {@code call} inside a started observation named {@code name}, tagging it with the (high-cardinality)
     * repository and tenant and letting the call attach any low-cardinality outcome to the observation it receives.
     * An error thrown by the call is recorded on the observation and rethrown; the observation is always stopped.
     */
    public static <T> T observe(ObservationRegistry registry, String name, String repository, String tenant,
                                ObservedCall<T> call) throws IOException {
        Observation observation = Observation.createNotStarted(name, registry)
                .highCardinalityKeyValue("repository", repository == null ? "none" : repository)
                .highCardinalityKeyValue("tenant", tenant == null ? "none" : tenant);
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            return call.call(observation);
        } catch (IOException | RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /** The body of an observed operation, handed the live {@link Observation} so it can add a low-card outcome tag. */
    @FunctionalInterface
    public interface ObservedCall<T> {
        T call(Observation observation) throws IOException;
    }
}
