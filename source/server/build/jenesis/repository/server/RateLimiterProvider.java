package build.jenesis.repository.server;

import build.jenesis.repository.store.Features;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A named factory for a {@link RateLimiter}, discovered at runtime with {@link ServiceLoader} - so the metering
 * strategy is a drop-in module (the in-memory token bucket; a coordinated limiter for a replicated deployment) and
 * the request filter names no implementation. Each provider reads its own configuration through the {@code config}
 * lookup (a property accessor returning {@code null} when unset). With no module installed, {@link #resolve}
 * answers {@link RateLimiter#NONE}: nothing is limited.
 */
public interface RateLimiterProvider {

    /** The limiter name this provider answers to, e.g. {@code token-bucket}. */
    String name();

    /** Build the limiter if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<RateLimiter> create(UnaryOperator<String> config);

    /** The config keys this limiter cannot run without; empty (the default) for one that needs nothing. A provider
     *  whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The first enabled limiter discovered via {@link ServiceLoader} (an exclusive SPI: an explicit
     *  {@code jenesis.repository.rate-limiter=<name>} selects one by name, a {@code jenesis.repository.<name>=false}
     *  skips one, {@link Features}), or {@link RateLimiter#NONE} when none answers. */
    static RateLimiter resolve(UnaryOperator<String> config) {
        Optional<String> selection = Features.selection("rate-limiter");
        for (RateLimiterProvider provider : ServiceLoader.load(RateLimiterProvider.class)) {
            if (selection.isPresent()
                    ? !provider.name().equalsIgnoreCase(selection.get())
                    : !Features.active(provider.name(), provider.requiredConfig())) {
                continue;
            }
            Optional<RateLimiter> limiter = provider.create(config);
            if (limiter.isPresent()) {
                return limiter.get();
            }
        }
        return RateLimiter.NONE;
    }
}
