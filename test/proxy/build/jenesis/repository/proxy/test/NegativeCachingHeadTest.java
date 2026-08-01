package build.jenesis.repository.proxy.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.proxy.NegativeCachingFetcher;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The negative cache remembers an upstream {@code 404} across all three verbs, {@link NegativeCachingFetcher#head}
 * included: a HEAD probes the same URL a fetch/download would, so a definite HEAD {@code 404} is remembered and a
 * second HEAD of that URL is answered from memory without reaching the delegate - keeping the cache consistent with
 * the fetch/download cases and sparing the upstream a repeat probe. A counting delegate proves the second HEAD never
 * reaches it.
 */
class NegativeCachingHeadTest {

    private static final URI MISSING = URI.create("https://upstream.example/org/x/1.0/x-1.0.jar.sha256");

    @Test
    void a_head_404_is_remembered_and_the_second_head_is_answered_from_memory() throws IOException {
        CountingHeadFetcher delegate = new CountingHeadFetcher(404);
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(delegate, Duration.ofSeconds(60));

        ProxyFormat.Head first = fetcher.head(MISSING, Map.of()).orElseThrow();
        assertThat(first.status()).isEqualTo(404);
        assertThat(delegate.heads).as("the first HEAD reaches the upstream").isEqualTo(1);

        ProxyFormat.Head second = fetcher.head(MISSING, Map.of()).orElseThrow();
        assertThat(second.status()).as("the remembered 404 answers the second HEAD").isEqualTo(404);
        assertThat(delegate.heads).as("the second HEAD is served from memory, never reaching the delegate")
                .isEqualTo(1);
        assertThat(fetcher.metrics()).singleElement().extracting(metric -> metric.value())
                .isEqualTo(1.0); // exactly one remembered miss
    }

    @Test
    void a_head_200_is_never_remembered_so_it_always_reaches_the_delegate() throws IOException {
        CountingHeadFetcher delegate = new CountingHeadFetcher(200);
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(delegate, Duration.ofSeconds(60));

        fetcher.head(MISSING, Map.of());
        fetcher.head(MISSING, Map.of());

        assertThat(delegate.heads).as("a success is transient state, never cached - every HEAD hits the upstream")
                .isEqualTo(2);
        assertThat(fetcher.metrics()).singleElement().extracting(metric -> metric.value()).isEqualTo(0.0);
    }

    /** A delegate that answers every HEAD with a fixed status and counts how many HEADs actually reached it. */
    private static final class CountingHeadFetcher implements ProxyFormat.Fetcher {
        private final int status;
        private int heads;

        private CountingHeadFetcher(int status) {
            this.status = status;
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
            return Optional.of(new ProxyFormat.Fetched(status, new byte[0], Map.of()));
        }

        @Override
        public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) {
            heads++;
            return Optional.of(new ProxyFormat.Head(status, Map.of()));
        }
    }
}
