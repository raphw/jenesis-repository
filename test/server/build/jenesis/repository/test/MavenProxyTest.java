package build.jenesis.repository.test;

import build.jenesis.repository.server.PullThroughCache;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.format.ProxyFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the pull-through cache against the real Maven Central: the {@link build.jenesis.repository.format.maven.MavenFormat}
 * proxy adapter, configured with Central as the upstream, fetches a real artifact on a miss, caches it and serves
 * the second read locally without a second fetch. Tagged {@code proxy} so it is network-gated, and self-skips when
 * Central is unreachable.
 */
@Tag("proxy")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MavenProxyTest {

    private static final String CENTRAL = "https://repo1.maven.org/maven2/";
    private static final String ARTIFACT =
            "/maven/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom";

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private AtomicInteger fetches;

    @BeforeAll
    public void start() {
        assumeTrue(reachable("repo1.maven.org", 443), "Maven Central must be reachable");
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        ProxyFormat.Fetcher upstream = PullThroughCache.http();
        fetches = new AtomicInteger();
        ProxyFormat.Fetcher counting = (url, requestHeaders) -> {
            fetches.incrementAndGet();
            return upstream.fetch(url, requestHeaders);
        };
        running = RepositoryApplication.start(0, Map.of("maven", URI.create(CENTRAL)), counting);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void stop() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void a_central_artifact_is_proxied_cached_then_served_locally() throws Exception {
        HttpResponse<byte[]> first = get(ARTIFACT);
        assertThat(first.statusCode()).as("proxied from Central").isEqualTo(200);
        assertThat(new String(first.body(), StandardCharsets.UTF_8)).contains("commons-lang3");
        assertThat(fetches.get()).as("the first read fetches from Central").isEqualTo(1);

        HttpResponse<byte[]> second = get(ARTIFACT);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(fetches.get()).as("the cached read does not touch Central").isEqualTo(1);

        assertThat(get("/maven/org/apache/commons/commons-lang3/0.0.0-none/commons-lang3-0.0.0-none.pom")
                .statusCode()).as("an artifact absent upstream is a 404").isEqualTo(404);
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofByteArray());
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
