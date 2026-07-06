package build.jenesis.repository.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A back-pressure-style accessor seam for authentication/authorization failures, mirroring
 * {@link RateLimitFilter#rejected()}: the wire components (the repository key entry point, and the OIDC/SAML login
 * failure handlers in the console) record a denial by its {@code mechanism} and HTTP {@code outcome}, and a metrics
 * layer scrapes the running totals into the {@code jenesis.auth.failures} meter. The free core stays registry-free -
 * this holds only plain counters, and the Micrometer adapter lives in the distribution (enterprise
 * {@code GovernanceMetrics}). The set of (mechanism, outcome) pairs is small and bounded ({@code key|oidc|saml} ×
 * {@code 401|403}), so a reader can register one counter per pair up front.
 */
public final class AuthFailures {

    private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();

    /** Record one denial: {@code mechanism} is {@code key}/{@code oidc}/{@code saml}, {@code outcome} the HTTP status. */
    public void record(String mechanism, int outcome) {
        counts.computeIfAbsent(mechanism + ":" + outcome, key -> new AtomicLong()).incrementAndGet();
    }

    /** The number of denials seen for this {@code mechanism}/{@code outcome} pair since startup - a monotonic total. */
    public long count(String mechanism, int outcome) {
        AtomicLong counter = counts.get(mechanism + ":" + outcome);
        return counter == null ? 0L : counter.get();
    }
}
