/**
 * Request rate limiting as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.server.spi.RateLimiterProvider} answering to {@code token-bucket}, metering each key
 * against an in-memory bucket that refills at the requested rate and holds one window's burst. Per process - in a
 * replicated deployment each node limits independently, the usual cheap trade for keeping a coordination service
 * off the hot path; a coordinated limiter would be another module. A deployment without this module never limits.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.ratelimit {
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.observation;
    exports build.jenesis.repository.ratelimit to build.jenesis.repository.test, build.jenesis.repository.ratelimit.test;
    provides build.jenesis.repository.server.spi.RateLimiterProvider
            with build.jenesis.repository.ratelimit.TokenBucketRateLimiterProvider;
}
