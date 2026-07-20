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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fetcher follows redirects itself so it can drop a caller credential when the chain crosses to another origin -
 * an importer download or a proxy fetch that a legitimate server 302s to a presigned object-store URL must not carry
 * the operator's {@code Authorization} to that third host, but a same-origin redirect keeps it - and so it can refuse
 * a redirect that aims the fetch at a private/loopback/cloud-metadata host (an SSRF the up-front import screen cannot
 * see, the target being chosen by the upstream). The header tests inject a permissive host screen so their loopback
 * fixtures stand in for public hosts; {@link #a_redirect_to_a_private_host_is_refused} drives the shipped screen.
 */
class HttpFetcherRedirectTest {

    // The loopback fixtures below stand in for public hosts, so the header behaviour is exercised without the shipped
    // private-range screen refusing the hop; the SSRF refusal itself is asserted by its own test with the real screen.
    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(10), host -> false);

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

    @Test
    void a_redirect_to_a_private_host_is_refused() throws IOException {
        Map<String, String> targetSaw = new ConcurrentHashMap<>();
        HttpServer target = server(exchange -> {
            targetSaw.put("hit", exchange.getRequestURI().toString());
            body(exchange, 200, "internal");
        });
        // The origin stands in for a public URL the trigger already vetted; the fetcher does not re-screen the initial
        // hop (that is the import trigger's job), so a loopback fixture reaches it, and it 302s the fetch onward to a
        // loopback host - the metadata/control-plane SSRF the up-front import screen cannot see, the target being chosen
        // by the upstream. The fetcher is the shipped one, whose PrivateHosts screen refuses a redirect to 127.0.0.1.
        HttpServer origin = server(exchange ->
                redirect(exchange, "http://127.0.0.1:" + target.getAddress().getPort() + "/latest/meta-data/"));
        HttpFetcher guarded = new HttpFetcher(Duration.ofSeconds(10)); // shipped PrivateHosts screen, no permissive seam
        try {
            URI publicUrl = URI.create("http://127.0.0.1:" + origin.getAddress().getPort() + "/asset");

            assertThatThrownBy(() -> guarded.fetch(publicUrl, Map.of("Authorization", "Basic c3VwZXItc2VjcmV0")))
                    .as("a redirect onto a private/loopback host is an SSRF, refused rather than followed")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("SSRF");
            assertThat(targetSaw).as("the fetch (and its credential) never reaches the private redirect target")
                    .isEmpty();
        } finally {
            origin.stop(0);
            target.stop(0);
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
