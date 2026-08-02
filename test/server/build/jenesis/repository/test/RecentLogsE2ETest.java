package build.jenesis.repository.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;
import org.slf4j.LoggerFactory;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recent-logs endpoint end to end (WO.4, the free-core mirror): booted with authorization enforced, it proves the
 * bounded ring is readable through {@code GET /api/logs} and that the read is key-gated like every other {@code /api}
 * surface - no key is {@code 401}, an unprovisioned key {@code 403}, a {@code repository:read} key {@code 200} - and
 * that a line emitted after boot is tailed back through the {@code q} text search (the appender captured it live).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RecentLogsE2ETest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String root;
    private String reader;
    private String scoped;
    private String bogus;

    @BeforeAll
    public void boot() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("jenesis.repository.auth", "true");
        ArtifactStore backend = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? store.toString() : null);
        Authorization authorization = Authorization.enforcing(backend);
        reader = Authorization.mint("acme");
        authorization.grant(reader, "*", Authorization.REPOSITORY_READ);
        scoped = Authorization.mint("acme");
        authorization.grant(scoped, "acme-repo", Authorization.REPOSITORY_READ);   // read on ONE repository, not "*"
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
    }

    @Test
    public void the_logs_endpoint_is_key_gated_and_streams_the_recent_tail() throws Exception {
        assertThat(send("/api/logs", null).statusCode()).as("no key -> 401").isEqualTo(401);
        assertThat(send("/api/logs", bogus).statusCode()).as("an unprovisioned key -> 403").isEqualTo(403);

        HttpResponse<String> ok = send("/api/logs", reader);
        assertThat(ok.statusCode()).as("a deployment-wide (*) read key reads the tail").isEqualTo(200);
        assertThat(ok.body()).as("the read is the recent-logs tail document")
                .contains("\"cursor\"").contains("\"count\"").contains("\"entries\"");
    }

    @Test
    public void a_key_scoped_to_one_repository_cannot_read_the_deployment_wide_logs() throws Exception {
        // The tail is deployment-wide (every repository's log lines). A key granted read on ONE repository, not "*",
        // must not read it by naming its own repository - that is a cross-scope content leak. The route is authorized
        // against the deployment-wide scope "*", so only a wildcard grant satisfies it.
        assertThat(send("/api/logs", scoped).statusCode())
                .as("a repository-scoped read key is refused the deployment-wide logs").isEqualTo(403);
    }

    @Test
    public void a_line_emitted_after_boot_is_tailed_back_through_the_text_search() throws Exception {
        String marker = "recent-logs-e2e-marker-" + System.nanoTime();
        LoggerFactory.getLogger(RecentLogsE2ETest.class).warn(marker);
        HttpResponse<String> filtered = send("/api/logs?q=" + marker + "&level=WARN", reader);
        assertThat(filtered.statusCode()).isEqualTo(200);
        assertThat(filtered.body())
                .as("the appender captured the JVM's own log line and the q filter tails it back")
                .contains(marker).contains("\"level\":\"WARN\"");
    }

    private HttpResponse<String> send(String path, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + path)).GET();
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
