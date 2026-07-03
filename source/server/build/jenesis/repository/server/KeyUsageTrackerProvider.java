package build.jenesis.repository.server;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.UnaryOperator;

/**
 * A named factory for a {@link KeyUsageTracker}, discovered at runtime with {@link ServiceLoader} - so credential
 * usage tracking is a drop-in module and the composition names no implementation. Each provider reads its own
 * configuration through the {@code config} lookup (a property accessor returning {@code null} when unset) and
 * records through the given {@link Authorization}. With no module installed, {@link #resolve} answers
 * {@link KeyUsageTracker#NONE}: nothing records and the worker reports as off.
 */
public interface KeyUsageTrackerProvider {

    /** The tracker name this provider answers to, e.g. {@code batching}. */
    String name();

    /** Build the tracker over the deployment's {@link Authorization}, reading settings through {@code config};
     *  empty when off. */
    Optional<KeyUsageTracker> create(Authorization authorization, UnaryOperator<String> config);

    /** The first tracker discovered via {@link ServiceLoader}, or {@link KeyUsageTracker#NONE} when no module is
     *  installed. */
    static KeyUsageTracker resolve(Authorization authorization, UnaryOperator<String> config) {
        for (KeyUsageTrackerProvider provider : ServiceLoader.load(KeyUsageTrackerProvider.class)) {
            Optional<KeyUsageTracker> tracker = provider.create(authorization, config);
            if (tracker.isPresent()) {
                return tracker.get();
            }
        }
        return KeyUsageTracker.NONE;
    }
}
