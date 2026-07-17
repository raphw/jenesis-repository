package build.jenesis.repository.test;

import build.jenesis.repository.server.BoundedTenantBuckets;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate-limit filter keys its buckets on the tenant read from an <em>un-authenticated</em> request key, whose
 * checksum is only a typo guard (a plain CRC32 anyone can compute), not a signature. So a flood of distinct
 * fabricated tenant names would otherwise mint an unbounded number of per-tenant buckets - a memory-exhaustion
 * vector. {@link BoundedTenantBuckets} caps how many distinct tenants ever get their own bucket; the rest, and every
 * keyless request, share one {@code anonymous} bucket.
 */
class BoundedTenantBucketsTest {

    @Test
    void a_keyless_request_meters_against_the_shared_anonymous_bucket() {
        BoundedTenantBuckets buckets = new BoundedTenantBuckets(100);
        assertThat(buckets.bucket(null)).isEqualTo("anonymous");
        assertThat(buckets.trackedTenants()).isZero();
    }

    @Test
    void a_real_tenant_keeps_its_own_bucket_across_requests() {
        BoundedTenantBuckets buckets = new BoundedTenantBuckets(100);
        assertThat(buckets.bucket("acme")).isEqualTo("acme");
        assertThat(buckets.bucket("acme")).isEqualTo("acme");
        assertThat(buckets.bucket("globex")).isEqualTo("globex");
        assertThat(buckets.trackedTenants()).isEqualTo(2);
    }

    @Test
    void a_flood_of_forged_tenants_cannot_mint_more_than_the_cap_of_buckets() {
        BoundedTenantBuckets buckets = new BoundedTenantBuckets(1000);
        Set<String> distinct = new HashSet<>();
        for (int index = 0; index < 100_000; index++) {
            distinct.add(buckets.bucket("forged-tenant-" + index));
        }
        // At most the cap of real buckets, plus the shared anonymous overflow.
        assertThat(buckets.trackedTenants()).isLessThanOrEqualTo(1000);
        assertThat(distinct).hasSizeLessThanOrEqualTo(1001);
        assertThat(distinct).contains("anonymous");
    }

    @Test
    void an_already_admitted_tenant_is_still_served_after_the_cap_fills() {
        BoundedTenantBuckets buckets = new BoundedTenantBuckets(10);
        assertThat(buckets.bucket("early")).isEqualTo("early");
        for (int index = 0; index < 1000; index++) {
            buckets.bucket("late-" + index);
        }
        // The early, already-admitted tenant keeps its dedicated bucket; late arrivals overflow to anonymous.
        assertThat(buckets.bucket("early")).isEqualTo("early");
        assertThat(buckets.bucket("brand-new")).isEqualTo("anonymous");
    }
}
