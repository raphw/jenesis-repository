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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Range and conditional requests, honoured format-agnostically at the serving layer over the raw format on the real
 * application: a {@code Range} over a streamed artifact is {@code 206} with a {@code Content-Range} and just the
 * requested bytes (a suffix range returns the tail, an out-of-bounds range is {@code 416}), so a large download is
 * resumable; and a buffered index carries an {@code ETag} of its bytes so a matching {@code If-None-Match} revalidates
 * to {@code 304}, so a client re-downloads a mutable index it already has only when it has actually changed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RangeRequestTest {

    private static final byte[] BODY = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = "/raw/dir/range.bin";

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void start() throws Exception {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + PATH))
                .PUT(BodyPublishers.ofByteArray(BODY)).build(), BodyHandlers.discarding()).statusCode())
                .isEqualTo(201);
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
    public void a_full_get_advertises_range_support() throws Exception {
        HttpResponse<byte[]> get = get(null);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.headers().firstValue("Accept-Ranges")).hasValue("bytes");
        assertThat(get.body()).isEqualTo(BODY);
    }

    @Test
    public void a_satisfiable_range_is_206_with_only_the_requested_bytes() throws Exception {
        HttpResponse<byte[]> get = get("bytes=2-5");
        assertThat(get.statusCode()).isEqualTo(206);
        assertThat(get.headers().firstValue("Content-Range")).hasValue("bytes 2-5/26");
        assertThat(new String(get.body(), StandardCharsets.UTF_8)).isEqualTo("CDEF");
    }

    @Test
    public void an_open_ended_range_serves_to_the_end() throws Exception {
        HttpResponse<byte[]> get = get("bytes=20-");
        assertThat(get.statusCode()).isEqualTo(206);
        assertThat(get.headers().firstValue("Content-Range")).hasValue("bytes 20-25/26");
        assertThat(new String(get.body(), StandardCharsets.UTF_8)).isEqualTo("UVWXYZ");
    }

    @Test
    public void a_suffix_range_serves_the_tail() throws Exception {
        HttpResponse<byte[]> get = get("bytes=-3");
        assertThat(get.statusCode()).isEqualTo(206);
        assertThat(get.headers().firstValue("Content-Range")).hasValue("bytes 23-25/26");
        assertThat(new String(get.body(), StandardCharsets.UTF_8)).isEqualTo("XYZ");
    }

    @Test
    public void an_out_of_bounds_range_is_416() throws Exception {
        HttpResponse<byte[]> get = get("bytes=100-200");
        assertThat(get.statusCode()).isEqualTo(416);
        assertThat(get.headers().firstValue("Content-Range")).hasValue("bytes */26");
    }

    @Test
    public void a_malformed_range_is_ignored_and_the_full_body_served() throws Exception {
        HttpResponse<byte[]> get = get("rows=1-2");
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(BODY);
    }

    @Test
    public void a_buffered_index_carries_an_etag_and_304s_when_unchanged() throws Exception {
        HttpResponse<byte[]> first = client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/"))
                .GET().build(), BodyHandlers.ofByteArray());
        assertThat(first.statusCode()).isEqualTo(200);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).startsWith("\"").endsWith("\"");

        HttpResponse<byte[]> revalidate = client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/"))
                .header("If-None-Match", etag).GET().build(), BodyHandlers.ofByteArray());
        assertThat(revalidate.statusCode()).as("an unchanged index is not re-sent").isEqualTo(304);
        assertThat(revalidate.body()).isEmpty();

        HttpResponse<byte[]> stale = client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/"))
                .header("If-None-Match", "\"0000\"").GET().build(), BodyHandlers.ofByteArray());
        assertThat(stale.statusCode()).as("a non-matching validator gets the full body").isEqualTo(200);
        assertThat(stale.body()).isEqualTo(first.body());
    }

    private HttpResponse<byte[]> get(String range) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + PATH)).GET();
        if (range != null) {
            request.header("Range", range);
        }
        return client.send(request.build(), BodyHandlers.ofByteArray());
    }
}
