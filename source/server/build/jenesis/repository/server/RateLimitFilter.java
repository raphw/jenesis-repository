package build.jenesis.repository.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sheds excess load before the request reaches the repository: each request is metered against its tenant's rate
 * ceiling (the per-tenant {@link Authorization#rateLimit} when set, otherwise the deployment default), and one that
 * exhausts the tenant's {@link RateLimiter} bucket is answered {@code 429 Too Many Requests} with a {@code
 * Retry-After}. The tenant is read from the {@code Jenesis-Repository-Key} header; a keyless request meters against
 * a shared {@code anonymous} bucket on the default. The Actuator endpoints are never limited, so liveness and
 * scrape probes are unaffected. The effective ceiling is cached briefly per tenant so the limiter, not a store
 * read, is on the hot path. A ceiling of zero (nothing configured) is unlimited - the filter is then a no-op.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L;

    private final RateLimiter limiter;
    private final Authorization authorization;
    private final long defaultPermitsPerMinute;
    private final ConcurrentHashMap<String, long[]> ceilings = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();

    public RateLimitFilter(RateLimiter limiter, Authorization authorization, long defaultPermitsPerMinute) {
        this.limiter = limiter;
        this.authorization = authorization;
        this.defaultPermitsPerMinute = defaultPermitsPerMinute;
    }

    /** The number of requests shed with {@code 429} since startup - a back-pressure signal a metrics layer can scrape. */
    public long rejected() {
        return rejected.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }
        String tenant = Authorization.tenantOf(request.getHeader("Jenesis-Repository-Key"));
        String bucket = tenant == null ? "anonymous" : tenant;
        if (!limiter.allow(bucket, ceiling(bucket, tenant))) {
            rejected.incrementAndGet();
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            return;
        }
        chain.doFilter(request, response);
    }

    private long ceiling(String bucket, String tenant) {
        long now = System.nanoTime();
        long[] cached = ceilings.get(bucket);
        if (cached != null && cached[1] > now) {
            return cached[0];
        }
        long ceiling = defaultPermitsPerMinute;
        if (tenant != null) {
            try {
                long override = authorization.rateLimit(tenant);
                if (override > 0) {
                    ceiling = override;
                }
            } catch (IOException e) {
                // best-effort: fall back to the deployment default
            }
        }
        ceilings.put(bucket, new long[]{ceiling, now + CACHE_TTL_NANOS});
        return ceiling;
    }
}
