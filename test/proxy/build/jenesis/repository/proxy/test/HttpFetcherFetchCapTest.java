package build.jenesis.repository.proxy.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.HttpFetcher;
import module jdk.httpserver;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The buffered {@link HttpFetcher#fetch} path caps the response body at {@code MAX_FETCH_BODY} (64 MiB): {@code fetch}
 * reads through a bounded {@code readNBytes(MAX_FETCH_BODY + 1)} rather than {@code ofByteArray()}, so a hostile,
 * compromised or MITM-substituted upstream that returns a multi-GB "index" is refused with an {@link IOException}
 * before it materialises on the heap, while a body at or under the ceiling is served whole. A fixture upstream stands
 * in for the substituted origin, streaming a chosen number of bytes from a small buffer so the test never holds the
 * whole body itself; the loopback host stands in for a public one (a permissive redirect screen), the cap being
 * orthogonal to the SSRF screen.
 */
class HttpFetcherFetchCapTest {

    private static final int MAX_FETCH_BODY = 64 * 1024 * 1024;

    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(30), host -> false);

    @Test
    void a_body_over_the_cap_is_refused_with_a_fetch_limit_error() throws IOException {
        HttpServer server = server(MAX_FETCH_BODY + 1); // one byte past the ceiling
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/index");

            assertThatThrownBy(() -> fetcher.fetch(url, Map.of()))
                    .as("an oversized index body is refused before it can OOM the proxy")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("fetch limit");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_body_exactly_at_the_cap_is_served_whole() throws IOException {
        HttpServer server = server(MAX_FETCH_BODY); // exactly the ceiling - the last size that is allowed
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/index"), Map.of())
                    .orElseThrow();

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(fetched.body()).as("a body exactly at the cap is not clipped").hasSize(MAX_FETCH_BODY);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_body_well_under_the_cap_is_served_whole() throws IOException {
        HttpServer server = server(4096);
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/index"), Map.of())
                    .orElseThrow();

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(fetched.body()).hasSize(4096);
        } finally {
            server.stop(0);
        }
    }

    /** A fixture upstream answering {@code /index} with exactly {@code length} bytes, written from a small reusable
     *  buffer so the server never holds the whole body; a client that aborts the oversized read closes the connection,
     *  which surfaces here as a write failure that is ignored (the refusal is the point). */
    private static HttpServer server(long length) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, length);
                byte[] chunk = new byte[64 * 1024];
                long remaining = length;
                OutputStream out = exchange.getResponseBody();
                while (remaining > 0) {
                    int step = (int) Math.min(chunk.length, remaining);
                    out.write(chunk, 0, step);
                    remaining -= step;
                }
            } catch (IOException _) {
                // the client refused the oversized body and closed the connection - expected for the over-cap case
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
