/**
 * Request rate limiting as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.RateLimiterProvider} answering to {@code token-bucket}, metering each key
 * against an in-memory bucket that refills at the requested rate and holds one window's burst. Per process - in a
 * replicated deployment each node limits independently, the usual cheap trade for keeping a coordination service
 * off the hot path; a coordinated limiter would be another module. A deployment without this module never limits.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.ratelimit {
    requires build.jenesis.repository.server;
    exports build.jenesis.repository.ratelimit to build.jenesis.repository.test;
    provides build.jenesis.repository.server.RateLimiterProvider
            with build.jenesis.repository.ratelimit.TokenBucketRateLimiterProvider;
}
