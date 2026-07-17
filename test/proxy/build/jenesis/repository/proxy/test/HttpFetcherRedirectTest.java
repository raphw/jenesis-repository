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
 * The fetcher follows redirects itself so it can drop a caller credential when the chain crosses to another origin -
 * an importer download or a proxy fetch that a legitimate server 302s to a presigned object-store URL must not carry
 * the operator's {@code Authorization} to that third host, but a same-origin redirect keeps it.
 */
class HttpFetcherRedirectTest {

    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(10));

    @Test
    void a_cross_origin_redirect_drops_the_authorization_header() throws IOException {
        Map<String, String> targetSawHeaders = new ConcurrentHashMap<>();
        HttpServer target = server(exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                targetSawHeaders.put("Authorization", auth);
            }
            body(exchange, 200, "landed");
        });
        HttpServer origin = server(exchange ->
                redirect(exchange, "http://127.0.0.1:" + target.getAddress().getPort() + "/blob"));
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + origin.getAddress().getPort() + "/asset"),
                    Map.of("Authorization", "Basic c3VwZXItc2VjcmV0")).orElseThrow();

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(new String(fetched.body(), StandardCharsets.UTF_8)).isEqualTo("landed");
            assertThat(targetSawHeaders).as("the credential must not travel to the other-origin redirect target")
                    .doesNotContainKey("Authorization");
        } finally {
            origin.stop(0);
            target.stop(0);
        }
    }

    @Test
    void a_same_origin_redirect_keeps_the_authorization_header() throws IOException {
        Map<String, String> secondHopSawHeaders = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset", exchange -> redirect(exchange, "/blob")); // relative -> same origin
        server.createContext("/blob", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                secondHopSawHeaders.put("Authorization", auth);
            }
            body(exchange, 200, "landed");
        });
        server.start();
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/asset"),
                    Map.of("Authorization", "Basic c3VwZXItc2VjcmV0")).orElseThrow();

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(secondHopSawHeaders).as("a same-origin redirect keeps the credential")
                    .containsEntry("Authorization", "Basic c3VwZXItc2VjcmV0");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void body(com.sun.net.httpserver.HttpExchange exchange, int status, String content)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
