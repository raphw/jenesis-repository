package build.jenesis.repository.test;

import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

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
    public void a_key_is_refused_from_an_address_outside_its_allowlist_and_admitted_from_within() throws Exception {
        // A deploy from loopback (ci carries no allowlist, so any address is admitted) seeds an artifact to read.
        assertThat(put("maven/org/example/ip/1/ip-1.jar", ci).statusCode()).isEqualTo(201);
        // offnet has repository:read but an allowlist of 10.0.0.0/8; the test client connects over loopback, which is
        // outside it, so an otherwise-valid key is forbidden - the allowlist is enforced on the request path, not just
        // stored. allowlisted carries the same read right and an allowlist that includes loopback, so it reads.
        assertThat(get("maven/org/example/ip/1/ip-1.jar", offnet).statusCode()).isEqualTo(403);
        assertThat(get("maven/org/example/ip/1/ip-1.jar", allowlisted).statusCode()).isEqualTo(200);
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
