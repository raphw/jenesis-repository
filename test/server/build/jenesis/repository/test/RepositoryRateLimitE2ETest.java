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
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the free server with a request rate ceiling and proves it sheds excess load: a burst of requests is served
 * until the bucket drains, after which a further request is answered {@code 429 Too Many Requests} before it
 * reaches the repository.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryRateLimitE2ETest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void boot() {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("jenesis.repository.rate-limit", "5");
        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port() + "/";
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.rate-limit");
    }

    @Test
    public void a_burst_is_served_then_excess_requests_are_throttled() throws Exception {
        int[] statuses = new int[12];
        for (int request = 0; request < statuses.length; request++) {
            statuses[request] = client.send(
                    HttpRequest.newBuilder(URI.create(base + "maven/org/x/y/1/y-1.jar")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
        assertThat(statuses[0]).as("the first request is within the burst").isNotEqualTo(429);
        assertThat(Arrays.stream(statuses).anyMatch(status -> status == 429))
                .as("the ceiling throttles the excess").isTrue();
    }
}
