package build.jenesis.repository.test;

import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The multi-node consistency endpoint end to end (WCON.2, free core): booted with authorization enforced, it proves the
 * fleet view is readable through {@code GET /api/consistency} and that the read is key-gated like every other
 * {@code /api} surface - no key is {@code 401}, an unprovisioned key {@code 403}, a {@code repository:read} key
 * {@code 200} - and that a lone server <strong>degrades cleanly to single-node</strong>: its own published fingerprint
 * is reported as one live node with no divergence (a false positive is impossible), never an error. It detects and
 * reports; it never blocks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MultiNodeConsistencyE2ETest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String root;
    private String reader;
    private String bogus;

    @BeforeAll
    public void boot() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("jenesis.repository.auth", "true");
        System.setProperty("jenesis.consistency.enabled", "true");
        System.setProperty("jenesis.consistency.node-id", "e2e-node");
        ArtifactStore backend = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? store.toString() : null);
        Authorization authorization = Authorization.enforcing(backend);
        reader = Authorization.mint("acme");
        authorization.grant(reader, "*", Authorization.REPOSITORY_READ);
        bogus = Authorization.mint("acme");
        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        root = "http://localhost:" + server.port();
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty("jenesis.consistency.enabled");
        System.clearProperty("jenesis.consistency.node-id");
    }

    @Test
    public void the_consistency_endpoint_is_key_gated() throws Exception {
        assertThat(send(null).statusCode()).as("no key -> 401").isEqualTo(401);
        assertThat(send(bogus).statusCode()).as("an unprovisioned key -> 403").isEqualTo(403);
        assertThat(send(reader).statusCode()).as("a repository:read key reads the fleet view").isEqualTo(200);
    }

    @Test
    public void a_lone_server_degrades_to_single_node_with_no_divergence() throws Exception {
        HttpResponse<String> ok = send(reader);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body())
                .as("the read is the fleet document, reporting this lone node with no divergence")
                .contains("\"localNodeId\":\"e2e-node\"")
                .contains("\"nodeId\":\"e2e-node\"")
                .contains("\"liveCount\":1")
                .contains("\"singleNode\":true")
                .contains("\"converged\":true")
                .contains("\"divergences\":[]");
    }

    private HttpResponse<String> send(String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + "/api/consistency")).GET();
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
