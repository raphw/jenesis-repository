package build.jenesis.repository.proxy.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.HttpFetcher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fetcher answers a {@code HEAD} with a real HTTP {@code HEAD}: it returns the upstream status and response headers
 * with no body pulled - the size/metadata a repository serves a client {@code HEAD} from without fetching an uncached
 * large artifact - and follows redirects on the same manual chain as {@code GET}, dropping a caller credential when it
 * crosses to another origin. {@code Fetcher.NONE} answers empty here as it does for every capability.
 */
class HttpFetcherHeadTest {

    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(10));

    @Test
    void a_head_returns_status_and_headers_without_reading_a_body() throws IOException {
        Map<String, String> methodSeen = new ConcurrentHashMap<>();
        HttpServer server = server(exchange -> {
            methodSeen.put("method", exchange.getRequestMethod());
            // A real upstream answers a HEAD with the artifact's size and validators and no body; these pass-through
            // headers stand in for that metadata (Content-Length is server-managed, so it is not asserted here).
            exchange.getResponseHeaders().add("ETag", "\"abc\"");
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("X-Artifact-Length", "1048576");
            exchange.sendResponseHeaders(200, -1); // -1: headers only, no response body
            exchange.close();
        });
        try {
            ProxyFormat.Head head = fetcher.head(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/big-artifact"),
                    Map.of()).orElseThrow();

            assertThat(methodSeen).as("a genuine HTTP HEAD is issued, so the body is never pulled")
                    .containsEntry("method", "HEAD");
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.header("etag")).as("headers read case-insensitively").isEqualTo("\"abc\"");
            assertThat(head.header("Content-Type")).isEqualTo("application/octet-stream");
            assertThat(head.header("X-Artifact-Length")).isEqualTo("1048576");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_cross_origin_redirect_keeps_the_method_and_drops_the_authorization_header() throws IOException {
        Map<String, String> targetSaw = new ConcurrentHashMap<>();
        HttpServer target = server(exchange -> {
            targetSaw.put("method", exchange.getRequestMethod());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                targetSaw.put("Authorization", auth);
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer origin = server(exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + target.getAddress().getPort() + "/blob");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try {
            ProxyFormat.Head head = fetcher.head(
                    URI.create("http://127.0.0.1:" + origin.getAddress().getPort() + "/asset"),
                    Map.of("Authorization", "Basic c3VwZXItc2VjcmV0")).orElseThrow();

            assertThat(head.status()).isEqualTo(200);
            assertThat(targetSaw).as("the redirected request stays a HEAD").containsEntry("method", "HEAD");
            assertThat(targetSaw).as("the credential must not travel to the other-origin redirect target")
                    .doesNotContainKey("Authorization");
        } finally {
            origin.stop(0);
            target.stop(0);
        }
    }

    @Test
    void the_none_fetcher_answers_head_empty() throws IOException {
        assertThat(ProxyFormat.Fetcher.NONE.head(URI.create("http://example.invalid/x"), Map.of())).isEmpty();
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }
}
