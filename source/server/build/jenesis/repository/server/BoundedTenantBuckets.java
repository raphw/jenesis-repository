package build.jenesis.repository.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how many distinct tenants ever get their own rate-limit bucket, so an un-authenticated flood cannot exhaust
 * memory. The rate-limit filter runs <em>before</em> authentication and reads the tenant from the request key, but a
 * key's checksum is a plain {@link java.util.zip.CRC32} typo guard - anyone can compute a well-formed key for any
 * tenant name - so the tenant is attacker-controlled at this point. Left unbounded, a request stream cycling
 * fabricated tenant names would mint an unbounded number of buckets (and ceiling-cache and per-tenant-counter
 * entries) keyed by those names.
 *
 * <p>This admits at most {@code max} distinct tenants to their own bucket; every tenant beyond the cap, and every
 * keyless request, meters against the shared {@link #ANONYMOUS} bucket. A real deployment's tenant count sits far
 * below the cap, so a legitimate tenant that has been seen keeps its dedicated bucket; only an adversarial excess
 * spills into the shared bucket. The cap may be exceeded transiently by concurrent first-sightings of new tenants,
 * which is bounded by the number of in-flight requests and never grows without limit.
 */
public final class BoundedTenantBuckets {

    /** The shared bucket for keyless requests and for tenants admitted beyond the cap. */
    static final String ANONYMOUS = "anonymous";

    private final int max;
    private final Set<String> admitted = ConcurrentHashMap.newKeySet();

    public BoundedTenantBuckets(int max) {
        this.max = max;
    }

    /**
     * The bucket a request meters against: the tenant's own bucket when the tenant is already admitted or there is
     * still room under the cap, otherwise the shared {@link #ANONYMOUS} bucket. A {@code null} tenant (a keyless or
     * checksum-invalid request) always meters against {@code anonymous}.
     */
    public String bucket(String tenant) {
        if (tenant == null) {
            return ANONYMOUS;
        }
        if (admitted.contains(tenant)) {
            return tenant;
        }
        if (admitted.size() >= max) {
            return ANONYMOUS;
        }
        admitted.add(tenant);
        return tenant;
    }

    /** The number of tenants currently holding a dedicated bucket - the cardinality the cap bounds. */
    public int trackedTenants() {
        return admitted.size();
    }
}
