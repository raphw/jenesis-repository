package build.jenesis.repository.proxy.test;

import build.jenesis.repository.proxy.HttpFetcher;
import module jdk.httpserver;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fail-closed timeout: every request is bounded by the per-request timeout on top of the connect timeout, so a
 * stalled upstream - one that accepts the connection but never sends a response - cannot hang a proxy read or an
 * import forever. A timeout is reported as the contract's transport failure (an empty result) rather than an
 * exception, so the proxy lets the local {@code 404} stand and an import is refused rather than a {@code 5xx}
 * escaping. Exercised through the constructor's short-timeout seam against a fixture that sleeps well past it, for
 * each of {@link HttpFetcher#fetch}, {@link HttpFetcher#download} and {@link HttpFetcher#head}.
 */
class HttpFetcherTimeoutTest {

    // A tight per-request timeout; the fixture below sleeps far longer, so every verb trips the timeout deterministically.
    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofMillis(300), host -> false);

    @Test
    void fetch_download_and_head_each_fail_closed_to_empty_on_a_stalled_upstream() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(3000); // accept the connection, then stall well past the 300ms request timeout
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException | IOException _) {
                // the client already timed out and dropped the connection - nothing to answer
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stalled");

            assertThat(fetcher.fetch(url, Map.of())).as("a stalled fetch fails closed, not to a 5xx").isEmpty();
            assertThat(fetcher.download(url, Map.of())).as("a stalled download fails closed").isEmpty();
            assertThat(fetcher.head(url, Map.of())).as("a stalled head fails closed").isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
