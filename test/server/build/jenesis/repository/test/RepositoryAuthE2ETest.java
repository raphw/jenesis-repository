package build.jenesis.repository.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the credential model gates the wire. An enforcing {@link RepositoryApplication} answers 401
 * without a key, 403 for a key that lacks the required right, and 201/200 for a key carrying
 * {@code repository:write} / {@code repository:read} - the rights travelling in the {@code Jenesis-Repository-Key}
 * header, off the same grants the unit test exercises in isolation. The grants are written through an
 * {@link Authorization} over the same temporary store the server reads from, so the boot enforces them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryAuthE2ETest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;
    private String root;
    private String ci;
    private String ro;
    private String bogus;
    private String releasesRo;
    private String pathScoped;
    private String offnet;
    private String allowlisted;

    @BeforeAll
    public void boot() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("jenesis.repository.auth", "true");
        ArtifactStore backend = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? store.toString() : null);
        Authorization authorization = Authorization.enforcing(backend);
        ci = Authorization.mint("acme");
        ro = Authorization.mint("acme");
        bogus = Authorization.mint("acme");
        authorization.grant(ci, "*", Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE);
        authorization.grant(ro, "*", Authorization.REPOSITORY_READ);
        releasesRo = Authorization.mint("acme");
        authorization.grant(releasesRo, "releases", Authorization.REPOSITORY_READ);
        // A credential whose ONLY grant is path-scoped: repository:read under the maven/org/scoped subtree of any
        // repository ({@code *:<prefix>}), with no repository-wide right. It authorizes a read at or under that path
        // and is forbidden everywhere else - which only holds if the routed path reaches the grant check.
        pathScoped = Authorization.mint("acme");
        authorization.grant(pathScoped, "*:maven/org/scoped", Authorization.REPOSITORY_READ);
        // Two read keys carrying a source-IP allowlist: one that excludes loopback (so a request from the test client
        // is off-net) and one that includes it (so the same request is on-net), to prove the allowlist gates the wire.
        offnet = Authorization.mint("acme");
        authorization.grant(offnet, "*", Authorization.REPOSITORY_READ);
        authorization.setAllowedAddresses("acme", Authorization.hash(offnet), "10.0.0.0/8");
        allowlisted = Authorization.mint("acme");
        authorization.grant(allowlisted, "*", Authorization.REPOSITORY_READ);
        authorization.setAllowedAddresses("acme", Authorization.hash(allowlisted), "127.0.0.1,::1");
        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        root = "http://localhost:" + server.port();
        base = root + "/repository/";
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void a_deploy_without_a_key_is_unauthorized() throws Exception {
        assertThat(put("maven/org/example/a/1/a-1.jar", null).statusCode()).isEqualTo(401);
    }

    @Test
    public void a_read_only_key_may_not_deploy() throws Exception {
        assertThat(put("maven/org/example/b/1/b-1.jar", ro).statusCode()).isEqualTo(403);
    }

    @Test
    public void a_deploy_key_deploys_and_a_read_key_reads() throws Exception {
        assertThat(put("maven/org/example/c/1/c-1.jar", ci).statusCode()).isEqualTo(201);
        assertThat(get("maven/org/example/c/1/c-1.jar", ro).statusCode()).isEqualTo(200);
    }

    @Test
    public void an_unknown_key_is_forbidden() throws Exception {
        assertThat(get("maven/org/example/a/1/a-1.jar", bogus).statusCode()).isEqualTo(403);
    }

    @Test
    public void the_asset_enumeration_requires_a_read_key() throws Exception {
        // /api/assets is a read of the wire like any other - the export endpoint is not an open backdoor.
        assertThat(assets(null).statusCode()).isEqualTo(401);
        assertThat(assets(bogus).statusCode()).isEqualTo(403);
        assertThat(assets(ro).statusCode()).isEqualTo(200);
    }

    @Test
    public void a_repository_scoped_key_cannot_enumerate_another_repository() throws Exception {
        // releasesRo carries repository:read on "releases" only. It reads its own repo's assets, but the enumeration
        // is authorized against the effective ?repo=, not the routed name - so it cannot pivot to another repo by
        // passing repo=default (the header-authorizes-A / param-scopes-B mismatch). A wildcard read key still may.
        assertThat(assets(releasesRo, "releases").statusCode()).isEqualTo(200);
        assertThat(assets(releasesRo, "default").statusCode()).isEqualTo(403);
        assertThat(assets(ro, "default").statusCode()).isEqualTo(200);
    }

    @Test
    public void a_path_scoped_key_reads_within_its_prefix_and_is_forbidden_outside_it() throws Exception {
        // Seed one artifact inside the granted prefix and one outside it with the wildcard deploy key, so both exist
        // and any denial is an authorization verdict, not a 404. pathScoped carries repository:read only under
        // maven/org/scoped, so the routed path decides the outcome: a read inside the prefix is authorized (200), an
        // otherwise-identical read outside it is forbidden (403). Before the routed path was threaded into the grant
        // check the path-scoped grant matched nothing and even the in-prefix read was forbidden - a dead feature.
        assertThat(put("maven/org/scoped/lib/1/lib-1.jar", ci).statusCode()).isEqualTo(201);
        assertThat(put("maven/org/elsewhere/lib/1/lib-1.jar", ci).statusCode()).isEqualTo(201);
        assertThat(get("maven/org/scoped/lib/1/lib-1.jar", pathScoped).statusCode())
                .as("a read under the granted prefix is authorized").isEqualTo(200);
        assertThat(get("maven/org/elsewhere/lib/1/lib-1.jar", pathScoped).statusCode())
                .as("a read of an existing artifact outside the prefix is forbidden").isEqualTo(403);
    }

    @Test
    public void a_key_is_refused_from_an_address_outside_its_allowlist_and_admitted_from_within() throws Exception {
        // A deploy from loopback (ci carries no allowlist, so any address is admitted) seeds an artifact to read.
        assertThat(put("maven/org/example/ip/1/ip-1.jar", ci).statusCode()).isEqualTo(201);
        // offnet has repository:read but an allowlist of 10.0.0.0/8; the test client connects over loopback, which is
        // outside it, so an otherwise-valid key is forbidden - the allowlist is enforced on the request path, not just
        // stored. allowlisted carries the same read right and an allowlist that includes loopback, so it reads.
        assertThat(get("maven/org/example/ip/1/ip-1.jar", offnet).statusCode()).isEqualTo(403);
        assertThat(get("maven/org/example/ip/1/ip-1.jar", allowlisted).statusCode()).isEqualTo(200);
    }

    @Test
    public void the_api_reads_are_key_gated_like_every_other_wire_read() throws Exception {
        // W9.1: the /api reads ride the same deny-by-default RepositoryAuthorizationManager as every other surface - no
        // key is 401, and they are not an open backdoor that enumerates the deployment without a key. /api/capabilities
        // (the installed-feature list, not per-tenant data) is a repository:read surface like any other.
        assertThat(apiGet("/api/capabilities", null).statusCode()).as("no key -> 401").isEqualTo(401);
        assertThat(apiGet("/api/capabilities", ro).statusCode()).as("a repository:read key -> 200").isEqualTo(200);
    }

    @Test
    public void the_deployment_wide_posture_is_refused_a_repository_scoped_key() throws Exception {
        // /api/posture serves DEPLOYMENT-WIDE content - every tenant's unsafe-setting advisories (tenant, scope, the
        // exact jenesis.* key/value). Like /api/logs and /api/consistency it is bound to the deployment-wide scope "*",
        // so a key scoped to a single repository cannot read every other tenant's posture by naming its own repository
        // (the cross-scope leak class the /api/assets ?repo re-scope closes). No key is 401; a repository-scoped read key
        // is 403; only a wildcard ("*") read key reads the whole enumeration.
        assertThat(apiGet("/api/posture", null).statusCode()).as("no key -> 401").isEqualTo(401);
        assertThat(apiGet("/api/posture", releasesRo).statusCode())
                .as("a repository-scoped read key is refused the deployment-wide posture").isEqualTo(403);
        assertThat(apiGet("/api/posture", ro).statusCode())
                .as("a deployment-wide (*) read key reads the whole posture").isEqualTo(200);
    }

    @Test
    public void the_deployment_wide_actuator_is_refused_a_repository_scoped_key() throws Exception {
        // /actuator/metrics and /actuator/info serve DEPLOYMENT-WIDE operational data - Micrometer request
        // counts/URIs/statuses across every repository, JVM internals, build info. Like /api/logs|consistency|posture
        // they are bound to the deployment-wide scope "*", so a key scoped to a single repository cannot read them by
        // naming its own repository. /actuator/health stays permit-all (liveness), reachable without a "*" grant.
        assertThat(apiGet("/actuator/metrics", releasesRo).statusCode())
                .as("a repository-scoped read key is refused the deployment-wide actuator metrics").isEqualTo(403);
        assertThat(apiGet("/actuator/info", releasesRo).statusCode())
                .as("... and the deployment-wide actuator info").isEqualTo(403);
        assertThat(apiGet("/actuator/metrics", ro).statusCode())
                .as("a deployment-wide (*) read key reads the actuator metrics").isEqualTo(200);
        assertThat(apiGet("/actuator/health", releasesRo).statusCode())
                .as("actuator health stays permit-all, reachable by a repository-scoped key").isEqualTo(200);
    }

    @Test
    public void a_percent_encoded_deployment_wide_route_cannot_evade_the_scope_rebind() throws Exception {
        // The "*"-scope rebind classifies on the DECODED path Spring routes on, not the raw request URI. Spring matches
        // the mapping against the decoded path, so /api/po%73ture still routes to PostureController; without decoding
        // here a raw-URI equals() would miss it and revert to the caller's self-named scope, letting a repository-scoped
        // key read the deployment-wide posture. StrictHttpFirewall permits an encoded alphanumeric like %73, so only
        // decoding closes this. The repo-scoped key is refused the encoded route; the wildcard key still reads it.
        assertThat(apiGet("/api/po%73ture", releasesRo).statusCode())
                .as("a percent-encoded deployment-wide route is refused a repository-scoped key, not bypassed")
                .isEqualTo(403);
        assertThat(apiGet("/api/po%73ture", ro).statusCode())
                .as("a wildcard (*) key still reads the route on its decoded path").isEqualTo(200);
    }

    @Test
    public void the_admin_import_trigger_is_authorized_as_a_write() throws Exception {
        // W9.1: POST /repository/admin/import is a state-changing background-job trigger; it is a repository:write like
        // any other mutation. No key is 401; a repository:read-only key is refused (403); a write key clears
        // authorization and reaches the controller (never 401/403 - the missing body/target then answers a 4xx of its
        // own, which is not an authorization verdict).
        assertThat(apiPost("/repository/admin/import", null).statusCode()).as("no key -> 401").isEqualTo(401);
        assertThat(apiPost("/repository/admin/import", ro).statusCode()).as("a read-only key -> 403").isEqualTo(403);
        assertThat(apiPost("/repository/admin/import", ci).statusCode())
                .as("a write key clears authorization (reaches the controller, never 401/403)").isNotIn(401, 403);
    }

    @Test
    public void an_anonymous_read_grant_does_not_open_the_deployment_wide_operator_observability() throws Exception {
        // Regression for an anonymous-grant cross-scope disclosure. With the public-mirror opt-in
        // jenesis.repository.anonymous-rights=repository:read enabled, the anonymous grant parses to the WILDCARD scope
        // "*" - exactly what the deployment-wide operator routes (/api/logs, /api/consistency, the /actuator subtree)
        // are rebound to - so a completely KEYLESS caller would satisfy covers("*","*",path)+grantedBy("repository:read")
        // and read the fleet log ring / whole-fleet consistency state / actuator metrics with no key at all. These
        // operator-observability routes must require an authenticated key even when the anonymous role is on. /api/posture
        // stays intentionally anonymous (a public advisory), and the anonymous artifact mirror keeps working. Booted as a
        // second server (sharing this class's store, so the ci/ro grants stay valid) with the anonymous role enabled.
        System.setProperty("jenesis.repository.anonymous-rights", "repository:read");
        try (RepositoryApplication.Running anon = RepositoryApplication.start(0)) {
            String anonRoot = "http://localhost:" + anon.port();
            // Keyless operator-observability reads are refused (403 FORBIDDEN), NOT served against the anonymous grant.
            // If the guard is reverted these become 200 (the leak), so each assertion is load-bearing.
            assertThat(anonGet(anonRoot + "/api/logs", null).statusCode())
                    .as("keyless /api/logs is refused even with the anonymous read grant").isEqualTo(403);
            assertThat(anonGet(anonRoot + "/api/consistency", null).statusCode())
                    .as("keyless /api/consistency is refused even with the anonymous read grant").isEqualTo(403);
            assertThat(anonGet(anonRoot + "/actuator/metrics", null).statusCode())
                    .as("keyless /actuator/metrics is refused even with the anonymous read grant").isEqualTo(403);
            // /api/posture stays intentionally anonymous - a keyless read is still served.
            assertThat(anonGet(anonRoot + "/api/posture", null).statusCode())
                    .as("/api/posture stays anonymous-readable, not caught by the operator guard").isEqualTo(200);
            // A wildcard ("*") read KEY still reads the operator view - the guard rejects only keyless callers.
            assertThat(anonGet(anonRoot + "/api/logs", ro).statusCode())
                    .as("a wildcard (*) read key still reads /api/logs").isEqualTo(200);
            // The anonymous artifact mirror is preserved: deploy with the write key, then a keyless GET still reads it.
            assertThat(anonPut(anonRoot + "/repository/maven/org/anon/m/1/m-1.jar", ci).statusCode()).isEqualTo(201);
            assertThat(anonGet(anonRoot + "/repository/maven/org/anon/m/1/m-1.jar", null).statusCode())
                    .as("a keyless artifact GET still works (anonymous mirror preserved)").isEqualTo(200);
        } finally {
            System.clearProperty("jenesis.repository.anonymous-rights");
        }
    }

    private HttpResponse<byte[]> anonGet(String url, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> anonPut(String url, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}));
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> apiGet(String path, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + path)).GET();
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> apiPost(String path, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> assets(String key) throws IOException, InterruptedException {
        return assets(key, "default");
    }

    private HttpResponse<byte[]> assets(String key, String repo) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + "/api/assets?repo=" + repo)).GET();
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> put(String path, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}));
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String path, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
