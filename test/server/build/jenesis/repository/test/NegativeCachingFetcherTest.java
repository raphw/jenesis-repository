package build.jenesis.repository.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.server.NegativeCachingFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The negative cache short-circuits a repeated upstream miss: a {@code 404} is remembered for the TTL and answered
 * from memory so the build tool's re-probes never reach the upstream, an entry expires so a genuinely published
 * artifact is seen, and a success or a transport failure is never cached. Covers both {@code fetch} (npm and the
 * rest) and {@code download} (Maven).
 */
class NegativeCachingFetcherTest {

    private static final URI URL = URI.create("https://upstream.example/org/x/1.0/x-1.0.jar");
    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-02T00:00:00Z"));

    @Test
    void a_repeated_404_is_answered_from_memory_within_the_ttl() throws IOException {
        AtomicInteger upstream = new AtomicInteger();
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(
                counting(upstream, 404), Duration.ofSeconds(60), clock);

        assertThat(fetcher.fetch(URL, Map.of())).hasValueSatisfying(f -> assertThat(f.status()).isEqualTo(404));
        assertThat(fetcher.fetch(URL, Map.of())).hasValueSatisfying(f -> assertThat(f.status()).isEqualTo(404));
        assertThat(upstream.get()).as("the second probe was answered from the cache").isEqualTo(1);
    }

    @Test
    void the_cached_404_expires_after_the_ttl() throws IOException {
        AtomicInteger upstream = new AtomicInteger();
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(
                counting(upstream, 404), Duration.ofSeconds(60), clock);

        fetcher.fetch(URL, Map.of());
        clock.advance(Duration.ofSeconds(61));
        fetcher.fetch(URL, Map.of());
        assertThat(upstream.get()).as("the upstream is re-probed once the entry has expired").isEqualTo(2);
    }

    @Test
    void a_success_is_never_cached() throws IOException {
        AtomicInteger upstream = new AtomicInteger();
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(
                counting(upstream, 200), Duration.ofSeconds(60), clock);

        fetcher.fetch(URL, Map.of());
        fetcher.fetch(URL, Map.of());
        assertThat(upstream.get()).as("a 200 is fetched every time").isEqualTo(2);
    }

    @Test
    void a_transport_failure_is_never_cached() throws IOException {
        AtomicInteger upstream = new AtomicInteger();
        ProxyFormat.Fetcher empty = new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                upstream.incrementAndGet();
                return Optional.empty();
            }
        };
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(empty, Duration.ofSeconds(60), clock);

        fetcher.fetch(URL, Map.of());
        fetcher.fetch(URL, Map.of());
        assertThat(upstream.get()).as("an empty (transport) result is retried, not cached").isEqualTo(2);
    }

    @Test
    void a_download_404_is_cached_too() throws IOException {
        AtomicInteger upstream = new AtomicInteger();
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(
                counting(upstream, 404), Duration.ofSeconds(60), clock);

        assertThat(fetcher.download(URL, Map.of())).hasValueSatisfying(d -> assertThat(d.status()).isEqualTo(404));
        assertThat(fetcher.download(URL, Map.of())).hasValueSatisfying(d -> assertThat(d.status()).isEqualTo(404));
        assertThat(upstream.get()).as("Maven's download path is cached like fetch").isEqualTo(1);
    }

    /** A fetcher that always answers with the given status, counting how often the upstream is actually reached. */
    private static ProxyFormat.Fetcher counting(AtomicInteger upstream, int status) {
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                upstream.incrementAndGet();
                return Optional.of(new ProxyFormat.Fetched(status, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) {
                upstream.incrementAndGet();
                return Optional.of(new ProxyFormat.Download(status, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
