package build.jenesis.repository.proxy.test;

import build.jenesis.repository.proxy.HttpFetcher;
import module org.junit.jupiter.api;

import module java.base;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fail-closed timeout: every request is bounded by the per-request timeout on top of the connect timeout, so a
 * stalled upstream - one that accepts the connection but does not answer before the deadline - cannot hang a proxy
 * read or an import forever. A timeout is reported as the contract's transport failure (an empty result) rather than
 * an exception, so the proxy lets the local {@code 404} stand and an import is refused rather than a {@code 5xx}
 * escaping. Exercised through the constructor's short-timeout seam against a WireMock upstream that delays its response
 * well past the timeout ({@code withFixedDelay}), for each of {@link HttpFetcher#fetch}, {@link HttpFetcher#download}
 * and {@link HttpFetcher#head}.
 */
class HttpFetcherTimeoutTest {

    // A tight per-request timeout; the upstream below delays far longer, so every verb trips the timeout deterministically.
    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofMillis(300), host -> false);

    @Test
    void fetch_download_and_head_each_fail_closed_to_empty_on_a_stalled_upstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        // Accept the connection, then delay the response 3s - well past the 300ms request timeout, so the client clips it.
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));
        try {
            URI url = URI.create("http://127.0.0.1:" + server.port() + "/stalled");

            assertThat(fetcher.fetch(url, Map.of())).as("a stalled fetch fails closed, not to a 5xx").isEmpty();
            assertThat(fetcher.download(url, Map.of())).as("a stalled download fails closed").isEmpty();
            assertThat(fetcher.head(url, Map.of())).as("a stalled head fails closed").isEmpty();
        } catch (IOException unexpected) {
            throw new AssertionError("a timeout must be reported as an empty result, not thrown", unexpected);
        } finally {
            server.stop();
        }
    }
}
