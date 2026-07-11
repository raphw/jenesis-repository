package build.jenesis.repository.server;

import build.jenesis.repository.store.Features;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
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

    /** The config keys this tracker cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The first enabled tracker discovered via {@link ServiceLoader} (an exclusive SPI: an explicit
     *  {@code jenesis.repository.key-usage=<name>} selects one by name, a {@code jenesis.repository.<name>=false}
     *  skips one, {@link Features}), or {@link KeyUsageTracker#NONE} when none answers. */
    static KeyUsageTracker resolve(Authorization authorization, UnaryOperator<String> config) {
        Optional<String> selection = Features.selection("key-usage");
        for (KeyUsageTrackerProvider provider : ServiceLoader.load(KeyUsageTrackerProvider.class)) {
            if (selection.isPresent()
                    ? !provider.name().equalsIgnoreCase(selection.get())
                    : !Features.active(provider.name(), provider.requiredConfig())) {
                continue;
            }
            Optional<KeyUsageTracker> tracker = provider.create(authorization, config);
            if (tracker.isPresent()) {
                return tracker.get();
            }
        }
        return KeyUsageTracker.NONE;
    }
}
