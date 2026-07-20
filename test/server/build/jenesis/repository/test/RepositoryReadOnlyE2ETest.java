package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the free server in read-only mode and proves the write-gate: a hosted publish and the import trigger are
 * refused with {@code 403}, while a read (a browse / download miss) still reaches the store and answers a normal
 * {@code 404} rather than being gated - so reads pass while writes are refused. The mode is advertised on
 * {@code /api/capabilities}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryReadOnlyE2ETest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void boot() {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("jenesis.repository.read-only", "true");
        // Auth now defaults on; the read-only gate (not the auth filter) must be what refuses the writes here, so pin
        // the anonymous (auth=false) opt-out to preserve the test's intent.
        System.setProperty("jenesis.repository.auth", "false");
        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port();
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty("jenesis.repository.read-only");
    }

    private int status(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    public void a_hosted_publish_is_refused() throws Exception {
        int status = status(HttpRequest.newBuilder(URI.create(base + "/repository/maven/org/x/a/1/a-1.jar"))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[64])).build());
        assertThat(status).as("a write is refused in read-only mode").isEqualTo(403);
    }

    @Test
    public void the_import_trigger_is_refused() throws Exception {
        int status = status(HttpRequest.newBuilder(URI.create(base + "/repository/admin/import"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        assertThat(status).as("the import trigger writes, so it is refused too").isEqualTo(403);
    }

    @Test
    public void a_read_miss_still_reaches_the_store() throws Exception {
        int status = status(HttpRequest.newBuilder(URI.create(base + "/repository/maven/org/x/a/1/a-1.jar"))
                .GET().build());
        assertThat(status).as("reads are not gated - an absent artifact is a normal 404, not a 403").isEqualTo(404);
    }

    @Test
    public void read_only_is_advertised_on_capabilities() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/capabilities")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"readOnly\":true");
    }
}
