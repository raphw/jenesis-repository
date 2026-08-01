package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-tenant rate ceiling override: a tenant whose {@link Authorization#rateLimit} differs from the deployment
 * default is metered at its own override, not the default. The store is pre-seeded with a low override for one tenant
 * while the deployment default stays generous, then the booted server is driven with that tenant's well-formed key -
 * a burst the default would never throttle is throttled at the tenant's ceiling - and a keyless burst of the same
 * size, metered against the anonymous bucket at the default, is not. The Actuator endpoints are never limited.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryTenantRateLimitE2ETest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;
    private String tenantKey;

    @BeforeAll
    public void boot() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        // A generous deployment default; a burst of ten never drains its 100-token bucket.
        System.setProperty("jenesis.repository.rate-limit", "100");
        // Auth on, so the Authorization is store-backed and reads the per-tenant override below (under auth=false it
        // is the storeless anonymous authorization, which has no per-tenant ceilings). The rate-limit filter runs
        // ahead of authentication, so a well-formed-but-unprovisioned key still meters against its tenant bucket.
        System.setProperty("jenesis.repository.auth", "true");

        ArtifactStore backing = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? store.toString() : null);
        Authorization authorization = Authorization.enforcing(backing);
        tenantKey = Authorization.mint("acme");
        authorization.setRateLimit("acme", 2);   // far below the deployment default

        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port() + "/repository/";
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.rate-limit");
        System.clearProperty("jenesis.repository.auth");
    }

    private int get(String url, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    public void a_tenant_meters_at_its_override_ceiling_not_the_deployment_default() throws Exception {
        boolean tenantThrottled = false;
        for (int request = 0; request < 10; request++) {
            if (get(base + "maven/org/x/y/1/y-1.jar", tenantKey) == 429) {
                tenantThrottled = true;
            }
        }
        assertThat(tenantThrottled)
                .as("the tenant's low override (2) throttles a burst the generous default (100) never would")
                .isTrue();

        boolean anonymousThrottled = false;
        for (int request = 0; request < 10; request++) {
            if (get(base + "maven/org/x/y/1/y-1.jar", null) == 429) {
                anonymousThrottled = true;
            }
        }
        assertThat(anonymousThrottled)
                .as("a keyless burst meters against the anonymous bucket at the generous default, unthrottled")
                .isFalse();
    }

    @Test
    public void the_actuator_endpoints_are_never_rate_limited() throws Exception {
        assertThat(get("http://localhost:" + server.port() + "/actuator/health", null))
                .as("actuator probes skip the limiter entirely").isNotEqualTo(429);
    }
}
