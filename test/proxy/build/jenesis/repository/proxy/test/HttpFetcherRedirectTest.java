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
 * The fetcher follows redirects itself so it can drop a caller credential when the chain crosses to another origin -
 * an importer download or a proxy fetch that a legitimate server 302s to a presigned object-store URL must not carry
 * the operator's {@code Authorization} to that third host, but a same-origin redirect keeps it - and so it can refuse
 * a redirect that aims the fetch at a private/loopback/cloud-metadata host (an SSRF the up-front import screen cannot
 * see, the target being chosen by the upstream). The header tests inject a permissive host screen so their loopback
 * WireMock fixtures stand in for public hosts; {@link #a_redirect_to_a_private_host_is_refused} drives the shipped
 * screen. The request journal proves which hop carried the credential.
 */
class HttpFetcherRedirectTest {

    // The loopback fixtures below stand in for public hosts, so the header behaviour is exercised without the shipped
    // private-range screen refusing the hop; the SSRF refusal itself is asserted by its own test with the real screen.
    private final HttpFetcher fetcher = new HttpFetcher(Duration.ofSeconds(10), host -> false);

    @Test
    void a_cross_origin_redirect_drops_the_authorization_header() throws IOException {
        WireMockServer target = start();
        target.stubFor(get(urlPathEqualTo("/blob")).willReturn(aResponse().withStatus(200).withBody("landed")));
        WireMockServer origin = start();
        origin.stubFor(get(urlPathEqualTo("/asset")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://127.0.0.1:" + target.port() + "/blob")));
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + origin.port() + "/asset"),
                    Map.of("Authorization", "Basic c3VwZXItc2VjcmV0")).orElseThrow();

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(new String(fetched.body(), StandardCharsets.UTF_8)).isEqualTo("landed");
            assertThat(target.getAllServeEvents()).as("the credential must not travel to the other-origin redirect target")
                    .allSatisfy(event -> assertThat(event.getRequest().getHeader("Authorization")).isNull());
        } finally {
            origin.stop();
            target.stop();
        }
    }

    @Test
    void a_same_origin_redirect_keeps_the_authorization_header() throws IOException {
        WireMockServer server = start();
        server.stubFor(get(urlPathEqualTo("/asset")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/blob"))); // relative -> same origin
        server.stubFor(get(urlPathEqualTo("/blob")).willReturn(aResponse().withStatus(200).withBody("landed")));
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + server.port() + "/asset"),
                    Map.of("Authorization", "Basic c3VwZXItc2VjcmV0")).orElseThrow();

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(server.getAllServeEvents().stream()
                    .filter(event -> "/blob".equals(pathOf(event.getRequest().getUrl())))
                    .toList())
                    .as("a same-origin redirect keeps the credential").isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getRequest().getHeader("Authorization"))
                            .isEqualTo("Basic c3VwZXItc2VjcmV0"));
        } finally {
            server.stop();
        }
    }

    @Test
    void a_redirect_loop_terminates_after_the_maximum_hops() throws IOException {
        // A stub that always 302s to itself - a redirect loop. The manual chain is bounded by MAX_REDIRECTS (5), so the
        // fetch cannot spin forever: after the fifth hop the guard stops following and returns the redirect response
        // itself (the last 302). The stub is hit MAX_REDIRECTS + 1 times - the initial request plus one re-issue per hop.
        WireMockServer server = start();
        server.stubFor(get(urlPathEqualTo("/loop")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/loop"))); // same-origin self-redirect
        try {
            ProxyFormat.Fetched fetched = fetcher.fetch(
                    URI.create("http://127.0.0.1:" + server.port() + "/loop"), Map.of()).orElseThrow();

            assertThat(fetched.status()).as("the loop terminates on the last redirect rather than following forever")
                    .isEqualTo(302);
            assertThat(server.getAllServeEvents()).as("the initial request plus exactly MAX_REDIRECTS followed hops")
                    .hasSize(6);
        } finally {
            server.stop();
        }
    }

    @Test
    void a_redirect_to_a_private_host_is_refused() throws IOException {
        WireMockServer target = start();
        target.stubFor(get(urlPathEqualTo("/latest/meta-data/")).willReturn(aResponse().withStatus(200).withBody("internal")));
        // The origin stands in for a public URL the trigger already vetted; the fetcher does not re-screen the initial
        // hop (that is the import trigger's job), so a loopback fixture reaches it, and it 302s the fetch onward to a
        // loopback host - the metadata/control-plane SSRF the up-front import screen cannot see, the target being chosen
        // by the upstream. The fetcher is the shipped one, whose PrivateHosts screen refuses a redirect to 127.0.0.1.
        WireMockServer origin = start();
        origin.stubFor(get(urlPathEqualTo("/asset")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://127.0.0.1:" + target.port() + "/latest/meta-data/")));
        HttpFetcher guarded = new HttpFetcher(Duration.ofSeconds(10)); // shipped PrivateHosts screen, no permissive seam
        try {
            URI publicUrl = URI.create("http://127.0.0.1:" + origin.port() + "/asset");

            assertThatThrownBy(() -> guarded.fetch(publicUrl, Map.of("Authorization", "Basic c3VwZXItc2VjcmV0")))
                    .as("a redirect onto a private/loopback host is an SSRF, refused rather than followed")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("SSRF");
            assertThat(target.getAllServeEvents()).as("the fetch (and its credential) never reaches the private redirect target")
                    .isEmpty();
        } finally {
            origin.stop();
            target.stop();
        }
    }

    private static String pathOf(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        return server;
    }
}
