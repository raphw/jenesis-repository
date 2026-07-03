package build.jenesis.repository.server;

/**
 * A request rate limiter, keyed by an arbitrary string (a tenant, or a credential hash): {@link #allow} consumes
 * one permit at the given ceiling and answers false when the key is over it. How permits are metered - an
 * in-memory token bucket, a coordinated store - is the implementation's part, supplied by a
 * {@link RateLimiterProvider} module discovered with {@link java.util.ServiceLoader}; with none installed
 * {@link #NONE} stands in and nothing is ever limited. A rate of zero or less always allows.
 */
@FunctionalInterface
public interface RateLimiter {

    /** The shared limiter standing in when no rate-limiting module is installed: every request is allowed. */
    RateLimiter NONE = (key, permitsPerMinute) -> true;

    /** Try to consume one permit for {@code key} at {@code permitsPerMinute}; false when the key is exhausted. */
    boolean allow(String key, double permitsPerMinute);
}
