package build.jenesis.repository.server;

import java.util.Optional;
import java.util.ServiceLoader;
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

    /** The first limiter discovered via {@link ServiceLoader}, or {@link RateLimiter#NONE} when no module is
     *  installed. */
    static RateLimiter resolve(UnaryOperator<String> config) {
        for (RateLimiterProvider provider : ServiceLoader.load(RateLimiterProvider.class)) {
            Optional<RateLimiter> limiter = provider.create(config);
            if (limiter.isPresent()) {
                return limiter.get();
            }
        }
        return RateLimiter.NONE;
    }
}
