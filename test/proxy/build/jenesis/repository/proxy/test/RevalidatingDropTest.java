package build.jenesis.repository.proxy.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.proxy.RevalidatingFetcher;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drop-and-subtract accounting the {@link RevalidatingFetcher} comment warns about: when a previously cached
 * index is superseded by a {@code 200} that carries no validator (or is oversized), the entry is dropped <em>and its
 * bytes subtracted from the running total</em>. A bare {@code cache.remove} would leak the accounting so
 * {@code jenesis.proxy.revalidation.bytes} drifts permanently high and the eviction loop later evicts every fresh
 * entry, silently degrading revalidation to a pass-through. This drives exactly that transition and asserts the byte
 * gauge returns to zero and the entry is gone.
 */
class RevalidatingDropTest {

    private static final URI INDEX = URI.create("https://upstream.example/org/x/index.json");

    @Test
    void a_no_validator_200_drops_the_prior_entry_and_subtracts_its_bytes() throws IOException {
        byte[] body = "cached-index-body".getBytes(StandardCharsets.UTF_8);
        // First fetch: a validated 200 the cache remembers; the second fetch of the same URL answers 200 with no
        // ETag/Last-Modified, so there is nothing to revalidate against and the entry must be dropped.
        SupersedingFetcher delegate = new SupersedingFetcher(body);
        RevalidatingFetcher fetcher = new RevalidatingFetcher(delegate);

        fetcher.fetch(INDEX, Map.of());
        assertThat(bytes(fetcher)).as("the validated body is remembered").isEqualTo((double) body.length);
        assertThat(entries(fetcher)).isEqualTo(1.0);

        ProxyFormat.Fetched second = fetcher.fetch(INDEX, Map.of()).orElseThrow();
        assertThat(second.status()).as("the no-validator 200 passes through to the caller").isEqualTo(200);
        assertThat(bytes(fetcher)).as("the dropped entry's bytes are subtracted, not leaked").isZero();
        assertThat(entries(fetcher)).as("the un-revalidatable entry is dropped").isZero();
    }

    private static double bytes(RevalidatingFetcher fetcher) {
        return metric(fetcher, "jenesis.proxy.revalidation.bytes");
    }

    private static double entries(RevalidatingFetcher fetcher) {
        return metric(fetcher, "jenesis.proxy.revalidation.entries");
    }

    private static double metric(RevalidatingFetcher fetcher, String name) {
        return fetcher.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .map(Metric::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name));
    }

    /** Answers the first fetch with a validated 200 (an {@code ETag} and a body, so the cache remembers it) and
     *  every subsequent fetch of the same URL with a 200 carrying no validator at all - the supersede that forces
     *  the drop-and-subtract path. */
    private static final class SupersedingFetcher implements ProxyFormat.Fetcher {
        private final byte[] body;
        private int calls;

        private SupersedingFetcher(byte[] body) {
            this.body = body;
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
            if (calls++ == 0) {
                return Optional.of(new ProxyFormat.Fetched(200, body, Map.of("ETag", "\"v1\"")));
            }
            return Optional.of(new ProxyFormat.Fetched(200, "fresh-but-unvalidated".getBytes(StandardCharsets.UTF_8),
                    Map.of())); // no ETag, no Last-Modified: nothing to revalidate against
        }
    }
}
