package build.jenesis.repository.ratelimit;

import build.jenesis.repository.server.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * An in-memory token-bucket rate limiter, keyed by an arbitrary string (a tenant, or a credential hash). Each key
 * gets a bucket that refills at the requested rate and holds up to one window's worth of burst; a request consumes
 * one token, and {@link #allow} is false when the bucket is empty. A rate of zero or less is unlimited. The rate is
 * passed per call rather than fixed at construction, so a configuration change takes effect on the next request
 * without rebuilding anything; the bucket simply refills and caps at the new rate.
 *
 * The limiter is per process - in a replicated deployment each node limits independently, so the effective ceiling
 * is the configured rate times the node count. That is the usual, cheap trade for not putting a coordination
 * service on the hot path; a single front door (or a small node count) keeps it close to the configured number.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public TokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    private TokenBucketRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** A limiter reading time from a supplied nanosecond clock instead of {@link System#nanoTime} (a test drives it). */
    public TokenBucketRateLimiter withClock(LongSupplier clock) {
        return new TokenBucketRateLimiter(clock);
    }

    @Override
    public boolean allow(String key, double permitsPerMinute) {
        if (permitsPerMinute <= 0) {
            return true;
        }
        return buckets.computeIfAbsent(key, ignored -> new Bucket()).tryAcquire(permitsPerMinute, clock.getAsLong());
    }

    private static final class Bucket {

        private double tokens = -1.0;
        private long lastNanos;

        synchronized boolean tryAcquire(double permitsPerMinute, long now) {
            double capacity = Math.max(1.0, permitsPerMinute);
            if (tokens < 0) {
                tokens = capacity;
                lastNanos = now;
            }
            tokens = Math.min(capacity, tokens + (now - lastNanos) * (permitsPerMinute / 60_000_000_000.0));
            lastNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
