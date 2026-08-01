package build.jenesis.repository.proxy.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.HttpFetcher;
import module org.junit.jupiter.api;

import module java.base;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The buffered {@link HttpFetcher#fetch} path caps the response body at {@code MAX_FETCH_BODY} (64 MiB): {@code fetch}
 * reads through a bounded {@code readNBytes(MAX_FETCH_BODY + 1)} rather than {@code ofByteArray()}, so a hostile,
 * compromised or MITM-substituted upstream that returns a multi-GB "index" is refused with an {@link IOException}
 * before it materialises on the heap, while a body at or under the ceiling is served whole. A WireMock upstream stands
 * in for the substituted origin, answering {@code /index} with a body of the chosen length; the loopback host stands
 * in for a public one (a permissive redirect screen), the cap being orthogonal to the SSRF screen.
 */
class HttpFetcherFetchCapTest {

    private static final int MAX_FETCH_BODY = 64 * 1024 * 1024;

    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(30), host -> false);

    @Test
    void a_body_over_the_cap_is_refused_with_a_fetch_limit_error() {
        WireMockServer server = serving(MAX_FETCH_BODY + 1); // one byte past the ceiling
        try {
            URI url = URI.create("http://127.0.0.1:" + server.port() + "/index");

            assertThatThrownBy(() -> fetcher.fetch(url, Map.of()))
                    .as("an oversized index body is refused before it can OOM the proxy")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("fetch limit");
        } finally {
            server.stop();
        }
    }

    @Test
    void a_body_exactly_at_the_cap_is_served_whole() throws IOException {
        WireMockServer server = serving(MAX_FETCH_BODY); // exactly the ceiling - the last size that is allowed
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + server.port() + "/index"), Map.of()).orElseThrow();

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(fetched.body()).as("a body exactly at the cap is not clipped").hasSize(MAX_FETCH_BODY);
        } finally {
            server.stop();
        }
    }

    @Test
    void a_body_well_under_the_cap_is_served_whole() throws IOException {
        WireMockServer server = serving(4096);
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + server.port() + "/index"), Map.of()).orElseThrow();

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(fetched.body()).hasSize(4096);
        } finally {
            server.stop();
        }
    }

    /** A WireMock upstream answering {@code /index} with exactly {@code length} bytes. */
    private static WireMockServer serving(int length) {
        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/index")).willReturn(aResponse().withStatus(200).withBody(new byte[length])));
        return server;
    }
}
