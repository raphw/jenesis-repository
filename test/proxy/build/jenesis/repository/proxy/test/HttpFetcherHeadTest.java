package build.jenesis.repository.proxy.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.HttpFetcher;
import module org.junit.jupiter.api;

import module java.base;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.RequestMethod;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fetcher answers a {@code HEAD} with a real HTTP {@code HEAD}: it returns the upstream status and response headers
 * with no body pulled - the size/metadata a repository serves a client {@code HEAD} from without fetching an uncached
 * large artifact - and follows redirects on the same manual chain as {@code GET}, dropping a caller credential when it
 * crosses to another origin. {@code Fetcher.NONE} answers empty here as it does for every capability. Driven against a
 * WireMock upstream whose request journal proves the issued method and the dropped header.
 */
class HttpFetcherHeadTest {

    // A permissive redirect-host screen: the loopback fixture in the cross-origin HEAD redirect test stands in for a
    // public host, so the shipped private-range screen does not refuse the hop (that refusal is HttpFetcherRedirectTest's).
    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(10), host -> false);

    @Test
    void a_head_returns_status_and_headers_without_reading_a_body() throws IOException {
        WireMockServer server = start();
        // A real upstream answers a HEAD with the artifact's size and validators and no body; these pass-through
        // headers stand in for that metadata (Content-Length is server-managed, so it is not asserted here).
        server.stubFor(head(urlPathEqualTo("/big-artifact")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"abc\"")
                .withHeader("Content-Type", "application/octet-stream")
                .withHeader("X-Artifact-Length", "1048576")));
        try {
            ProxyFormat.Head head = fetcher.head(
                    URI.create("http://127.0.0.1:" + server.port() + "/big-artifact"), Map.of()).orElseThrow();

            assertThat(server.getAllServeEvents()).as("a genuine HTTP HEAD is issued, so the body is never pulled")
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getRequest().getMethod()).isEqualTo(RequestMethod.HEAD));
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.header("etag")).as("headers read case-insensitively").isEqualTo("\"abc\"");
            assertThat(head.header("Content-Type")).isEqualTo("application/octet-stream");
            assertThat(head.header("X-Artifact-Length")).isEqualTo("1048576");
        } finally {
            server.stop();
        }
    }

    @Test
    void a_cross_origin_redirect_keeps_the_method_and_drops_the_authorization_header() throws IOException {
        WireMockServer target = start();
        target.stubFor(head(urlPathEqualTo("/blob")).willReturn(aResponse().withStatus(200)));
        WireMockServer origin = start();
        origin.stubFor(head(urlPathEqualTo("/asset")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://127.0.0.1:" + target.port() + "/blob")));
        try {
            ProxyFormat.Head head = fetcher.head(
                    URI.create("http://127.0.0.1:" + origin.port() + "/asset"),
                    Map.of("Authorization", "Basic c3VwZXItc2VjcmV0")).orElseThrow();

            assertThat(head.status()).isEqualTo(200);
            assertThat(target.getAllServeEvents()).as("the redirected request stays a HEAD")
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getRequest().getMethod()).isEqualTo(RequestMethod.HEAD));
            assertThat(target.getAllServeEvents()).as("the credential must not travel to the other-origin redirect target")
                    .allSatisfy(event -> assertThat(event.getRequest().getHeader("Authorization")).isNull());
        } finally {
            origin.stop();
            target.stop();
        }
    }

    @Test
    void the_none_fetcher_answers_head_empty() throws IOException {
        assertThat(ProxyFormat.Fetcher.NONE.head(URI.create("http://example.invalid/x"), Map.of())).isEmpty();
    }

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        return server;
    }
}
