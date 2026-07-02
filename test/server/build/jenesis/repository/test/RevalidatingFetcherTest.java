package build.jenesis.repository.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.RevalidatingFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The revalidating fetcher turns a re-fetch of a proxied mutable index into a conditional request: it remembers a
 * body with its validator, sends {@code If-None-Match} next time, serves the remembered body on a {@code 304}, and
 * refreshes on a changed {@code 200}. A response without a validator is not cached, so it is always fetched afresh.
 */
class RevalidatingFetcherTest {

    private static final URI URL = URI.create("https://upstream.example/org/x/index.json");

    @Test
    void an_unchanged_index_is_revalidated_and_served_from_the_remembered_body() throws IOException {
        Upstream upstream = new Upstream("\"v1\"", "index-body");
        RevalidatingFetcher fetcher = new RevalidatingFetcher(upstream);

        assertThat(fetcher.fetch(URL, Map.of()).orElseThrow().status()).isEqualTo(200);

        ProxyFormat.Fetched second = fetcher.fetch(URL, Map.of()).orElseThrow();
        assertThat(upstream.lastHeaders.get("If-None-Match")).as("the re-fetch is conditional").isEqualTo("\"v1\"");
        assertThat(second.status()).as("a 304 upstream is served as 200 from the remembered body").isEqualTo(200);
        assertThat(new String(second.body(), StandardCharsets.UTF_8)).isEqualTo("index-body");
    }

    @Test
    void a_changed_index_is_refreshed_and_re_remembered() throws IOException {
        Upstream upstream = new Upstream("\"v1\"", "old");
        RevalidatingFetcher fetcher = new RevalidatingFetcher(upstream);
        fetcher.fetch(URL, Map.of());

        upstream.change("\"v2\"", "new");
        ProxyFormat.Fetched refreshed = fetcher.fetch(URL, Map.of()).orElseThrow();
        assertThat(new String(refreshed.body(), StandardCharsets.UTF_8)).isEqualTo("new");

        fetcher.fetch(URL, Map.of());
        assertThat(upstream.lastHeaders.get("If-None-Match")).as("the new validator is used next").isEqualTo("\"v2\"");
    }

    @Test
    void a_response_without_a_validator_is_not_cached() throws IOException {
        ProxyFormat.Fetcher noValidator = new ProxyFormat.Fetcher() {
            private Map<String, String> seen;

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                seen = headers;
                return Optional.of(new ProxyFormat.Fetched(200, "body".getBytes(StandardCharsets.UTF_8), Map.of()));
            }
        };
        RevalidatingFetcher fetcher = new RevalidatingFetcher(noValidator);
        fetcher.fetch(URL, Map.of());
        fetcher.fetch(URL, Map.of());
        // the delegate never saw a conditional header, because nothing was worth remembering
        assertThat(fetcher.fetch(URL, Map.of("Accept", "application/json")).orElseThrow().status()).isEqualTo(200);
    }

    /** A fake upstream that answers a matching If-None-Match with 304, else a 200 carrying the current body + ETag. */
    private static final class Upstream implements ProxyFormat.Fetcher {

        private String etag;
        private byte[] body;
        private Map<String, String> lastHeaders;

        private Upstream(String etag, String body) {
            change(etag, body);
        }

        private void change(String etag, String body) {
            this.etag = etag;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
            lastHeaders = headers;
            if (etag.equals(headers.get("If-None-Match"))) {
                return Optional.of(new ProxyFormat.Fetched(304, new byte[0], Map.of("ETag", etag)));
            }
            return Optional.of(new ProxyFormat.Fetched(200, body, Map.of("ETag", etag)));
        }
    }
}
