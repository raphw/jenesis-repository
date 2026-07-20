package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.format.ProxyFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the pull-through cache through the raw format against a fixed in-memory upstream (no network): a path only
 * the upstream has is fetched, cached and then served as a local hit; an upstream miss is a 404; and a locally
 * published artifact never touches the upstream. Proves the {@link ProxyFormat} seam and {@code PullThroughCache}
 * end to end over the real {@link RepositoryApplication}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RawProxyTest {

    private static final byte[] UPSTREAM = "from the upstream".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OWN = "published locally".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private AtomicInteger fetches;

    @BeforeAll
    public void start() {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        Map<String, byte[]> upstream = Map.of("https://upstream.test/dir/file.bin", UPSTREAM);
        fetches = new AtomicInteger();
        ProxyFormat.Fetcher fetcher = (url, requestHeaders) -> {
            fetches.incrementAndGet();
            byte[] body = upstream.get(url.toString());
            return Optional.of(body == null
                    ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        };
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        running = RepositoryApplication.start(0, Map.of("raw", URI.create("https://upstream.test/")), fetcher);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void stop() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void a_miss_is_proxied_cached_and_then_served_locally() throws Exception {
        HttpResponse<byte[]> first = get("/raw/dir/file.bin");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(UPSTREAM);
        assertThat(fetches.get()).as("the first read fetches from the upstream").isEqualTo(1);

        HttpResponse<byte[]> second = get("/raw/dir/file.bin");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(UPSTREAM);
        assertThat(fetches.get()).as("the cached read does not touch the upstream").isEqualTo(1);

        assertThat(get("/raw/dir/absent.bin").statusCode()).as("an upstream miss is a 404").isEqualTo(404);

        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/raw/local/own.bin"))
                .PUT(BodyPublishers.ofByteArray(OWN)).build(), BodyHandlers.discarding()).statusCode())
                .isEqualTo(201);
        int before = fetches.get();
        HttpResponse<byte[]> local = get("/raw/local/own.bin");
        assertThat(local.statusCode()).isEqualTo(200);
        assertThat(local.body()).isEqualTo(OWN);
        assertThat(fetches.get()).as("a locally published artifact is not proxied").isEqualTo(before);
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofByteArray());
    }
}
