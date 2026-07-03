package build.jenesis.repository.ratelimit;

import java.util.Optional;
import java.util.function.UnaryOperator;
import build.jenesis.repository.server.RateLimiter;
import build.jenesis.repository.server.RateLimiterProvider;

/**
 * Discovers the in-memory token-bucket limiter. There is nothing to configure - the ceiling is passed per call, so
 * the deployment default and per-tenant overrides apply without rebuilding anything - so installing the module is
 * the switch.
 */
public final class TokenBucketRateLimiterProvider implements RateLimiterProvider {

    @Override
    public String name() {
        return "token-bucket";
    }

    @Override
    public Optional<RateLimiter> create(UnaryOperator<String> config) {
        return Optional.of(new TokenBucketRateLimiter());
    }
}
