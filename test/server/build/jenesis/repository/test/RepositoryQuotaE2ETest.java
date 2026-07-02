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
 * Boots the free server with a repository-wide storage quota and proves it caps stored content: the first artifact
 * fits and deploys ({@code 201}); once the quota is reached, a further new blob is refused with {@code 507
 * Insufficient Storage} before any bytes are stored, while a re-deploy of bytes already stored (deduped, no new
 * space) still succeeds.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryQuotaE2ETest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void boot() {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("jenesis.repository.quota", "1000");
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
        System.clearProperty("jenesis.repository.quota");
    }

    private int put(String path, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    public void the_quota_caps_new_content_but_lets_a_deduped_redeploy_through() throws Exception {
        byte[] full = new byte[1000];
        assertThat(put("maven/org/x/a/1/a-1.jar", full)).as("fills the quota exactly").isEqualTo(201);

        byte[] more = new byte[1];
        more[0] = 7;
        assertThat(put("maven/org/x/b/1/b-1.jar", more)).as("new bytes past the cap are refused").isEqualTo(507);

        assertThat(put("maven/org/x/a/1/a-1.jar", full)).as("already-stored bytes need no new space").isEqualTo(201);
    }
}
