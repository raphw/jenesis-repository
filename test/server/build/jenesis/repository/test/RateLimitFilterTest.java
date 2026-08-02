package build.jenesis.repository.test;

import build.jenesis.repository.server.RateLimitFilter;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct unit tests of {@link RateLimitFilter}, the load-shedding pre-auth filter, driven through its public
 * {@code doFilter} entry point with Mockito servlet-request/response mocks (the same inline-mock idiom
 * {@code RouteWritableTest} uses to drive the controller without booting the server) and a capturing
 * {@link RateLimiter} double that records the ceiling each request meters against. Two behaviours the end-to-end
 * rate-limit test cannot pin - the exact {@code Retry-After} header on a shed response, and the ceiling an
 * overflowed tenant meters against - are asserted here in isolation.
 *
 * <ul>
 *   <li>A shed request carries {@code Retry-After: 60} alongside the {@code 429} - the back-off hint a client honours
 *       - and never reaches the chain (the load is shed before the repository sees it).</li>
 *   <li>A forged tenant that overflows the filter's 50,000-tenant ({@code MAX_TRACKED_TENANTS}) bucket cap meters
 *       against the shared {@code anonymous} bucket at the <em>deployment default</em> ceiling, never its own cached
 *       per-tenant override. An overflowed tenant that still consulted its own ceiling would re-introduce the
 *       unbounded ceiling-cache (a fabricated-tenant memory-exhaustion vector) and let an attacker pick its own
 *       ceiling by forging a high per-tenant limit - the very regression the {@code effectiveTenant} downgrade guards.
 *       A control on a fresh filter proves the override is otherwise honoured, so the downgrade - not a dead override -
 *       is what pulls the overflowed request back to the default.</li>
 * </ul>
 */
public class RateLimitFilterTest {

    /** The deployment default ceiling wired into the filter; distinct from the per-tenant override so an assertion on
     *  the metered ceiling tells the two apart. */
    private static final long DEFAULT_CEILING = 7L;

    /** A per-tenant override, far above the default, provisioned for the forged tenant. An overflowed request must
     *  never meter against it. */
    private static final long TENANT_OVERRIDE = 100_000L;

    /** The filter's fixed distinct-tenant cap (package-private {@code RateLimitFilter.MAX_TRACKED_TENANTS}); filling it
     *  exactly forces the next distinct tenant to overflow into the shared {@code anonymous} bucket. */
    private static final int CAP = 50_000;

    @TempDir
    Path root;

    private Authorization authorization;

    /** A {@link RateLimiter} double that admits every request and records the bucket and ceiling of the most recent
     *  call, so a test can assert exactly what a metered request was measured against. */
    private static final class Capturing implements RateLimiter {
        private volatile String bucket;
        private volatile double ceiling = -1;

        @Override
        public boolean allow(String key, double permitsPerMinute) {
            this.bucket = key;
            this.ceiling = permitsPerMinute;
            return true;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
        // The forged tenant carries a high per-tenant override. It is honoured while the tenant holds its own bucket
        // and must be ignored once the tenant overflows to the shared anonymous bucket.
        authorization.setRateLimit("evil-corp", TENANT_OVERRIDE);
    }

    @Test
    void a_shed_request_carries_a_retry_after_header_and_never_reaches_the_chain() throws Exception {
        RateLimiter deny = (bucket, permitsPerMinute) -> false;
        RateLimitFilter filter = new RateLimitFilter(deny, Authorization.anonymous(), DEFAULT_CEILING);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/repository/maven/org/x/y/1/y-1.jar");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);

        int[] status = {-1};
        Map<String, String> headers = new HashMap<>();
        HttpServletResponse response = mock(HttpServletResponse.class);
        doAnswer(invocation -> status[0] = invocation.getArgument(0)).when(response).setStatus(anyInt());
        doAnswer(invocation -> headers.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(response).setHeader(anyString(), anyString());
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(status[0]).as("a rate-limited request is answered 429").isEqualTo(429);
        assertThat(headers).as("the 429 carries the Retry-After back-off hint the client honours")
                .containsEntry("Retry-After", "60");
        verify(chain, never()).doFilter(request, response);
        assertThat(filter.rejected()).as("the shed request is counted").isEqualTo(1);
        assertThat(filter.rejectedByTenant())
                .as("the keyless request meters against, and is tagged to, the shared anonymous bucket")
                .containsEntry("anonymous", 1L);
    }

    @Test
    void an_overflowed_forged_tenant_meters_at_the_default_ceiling_not_its_own_override() throws Exception {
        Capturing limiter = new Capturing();
        RateLimitFilter filter = new RateLimitFilter(limiter, authorization, DEFAULT_CEILING);

        // Drive one request each from CAP distinct well-formed tenants, so the filter's bucket table fills exactly to
        // its cap; the reused request mock answers the current tenant's key through a holder the loop advances.
        String[] key = new String[1];
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/repository/maven/org/x/y/1/y-1.jar");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getHeader("Jenesis-Repository-Key")).thenAnswer(invocation -> key[0]);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        for (int tenant = 0; tenant < CAP; tenant++) {
            key[0] = Authorization.mint("filler-" + tenant);
            filter.doFilter(request, response, chain);
            if (tenant % 1000 == 0) {
                clearInvocations(request, response, chain);   // bound the mock's recorded-invocation memory over the fill
            }
        }

        // The forged tenant is the (CAP+1)-th distinct tenant: its bucket is full, so it overflows to anonymous. Even
        // though it carries a high per-tenant override, the filter must meter it against the default ceiling.
        key[0] = Authorization.mint("evil-corp");
        filter.doFilter(request, response, chain);

        assertThat(limiter.bucket)
                .as("an overflowed tenant meters against the shared anonymous bucket, not a fresh per-tenant one")
                .isEqualTo("anonymous");
        assertThat(limiter.ceiling)
                .as("and at the deployment default ceiling - never its own forged per-tenant override, which would "
                        + "re-introduce the unbounded ceiling cache and let an attacker pick its own limit")
                .isEqualTo((double) DEFAULT_CEILING);
    }

    @Test
    void the_same_tenant_meters_at_its_own_override_while_it_holds_a_bucket() throws Exception {
        // The control: on a fresh filter the forged tenant is admitted to its own bucket (well under the cap), so its
        // per-tenant override IS honoured. This proves the override is live, so the overflow test's fall-back to the
        // default is the anonymous-bucket downgrade at work - not a dead or unread override.
        Capturing limiter = new Capturing();
        RateLimitFilter filter = new RateLimitFilter(limiter, authorization, DEFAULT_CEILING);

        String[] key = {Authorization.mint("evil-corp")};
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/repository/maven/org/x/y/1/y-1.jar");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getHeader("Jenesis-Repository-Key")).thenAnswer(invocation -> key[0]);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(limiter.bucket).as("an admitted tenant holds its own bucket").isEqualTo("evil-corp");
        assertThat(limiter.ceiling).as("and meters against its own per-tenant override")
                .isEqualTo((double) TENANT_OVERRIDE);
    }
}
