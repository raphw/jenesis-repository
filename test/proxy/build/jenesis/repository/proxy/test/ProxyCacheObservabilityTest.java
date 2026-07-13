package build.jenesis.repository.proxy.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.proxy.NegativeCachingFetcher;
import build.jenesis.repository.proxy.RevalidatingFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two composed proxy caches are each their own {@link build.jenesis.repository.observation.ObservabilitySource}:
 * the {@link NegativeCachingFetcher} reports the upstream misses it remembers as the bounded {@code
 * jenesis.proxy.negativecache.entries} gauge (against the map bound - a used-vs-available signal on the
 * memory-exhaustion vector the bound caps), and the {@link RevalidatingFetcher} reports the cached index bytes as the
 * bounded {@code jenesis.proxy.revalidation.bytes} gauge (against the byte ceiling) plus a plain {@code
 * jenesis.proxy.revalidation.entries} count, each with a presence health check and no background task. The signals
 * collect into the single {@link ObservabilityReport} view the distribution, Actuator and the docs all read.
 */
class ProxyCacheObservabilityTest {

    private static final URI URL = URI.create("https://upstream.example/org/x/1.0/x-1.0.jar");
    private static final URI INDEX = URI.create("https://upstream.example/org/x/index.json");

    @Test
    void a_fresh_negative_cache_reports_no_remembered_misses_as_a_bounded_gauge() {
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(status(404), Duration.ofSeconds(60));

        assertThat(fetcher.metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.name()).isEqualTo("jenesis.proxy.negativecache.entries");
            assertThat(metric.kind()).isEqualTo(Metric.Kind.GAUGE);
            assertThat(metric.value()).isZero();
            assertThat(metric.limit()).isPresent();           // bounded: it knows its ceiling
            assertThat(metric.usage()).hasValue(0.0);          // used vs available, no percentage pre-computed
        });
        assertThat(fetcher.taskStatuses()).isEmpty();
    }

    @Test
    void the_negative_cache_gauge_counts_only_the_distinct_remembered_misses() throws IOException {
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(status(404), Duration.ofSeconds(60));

        fetcher.fetch(URL, Map.of());
        fetcher.fetch(URL, Map.of());                          // same URL: still one remembered miss
        fetcher.fetch(URI.create("https://upstream.example/org/y/2.0/y-2.0.jar"), Map.of());

        assertThat(fetcher.metrics()).singleElement().extracting(Metric::value).isEqualTo(2.0);
    }

    @Test
    void a_success_is_never_remembered_so_it_does_not_move_the_gauge() throws IOException {
        NegativeCachingFetcher fetcher = new NegativeCachingFetcher(status(200), Duration.ofSeconds(60));

        fetcher.fetch(URL, Map.of());

        assertThat(fetcher.metrics()).singleElement().extracting(Metric::value).isEqualTo(0.0);
    }

    @Test
    void a_fresh_revalidation_cache_reports_zero_bytes_and_zero_entries() {
        RevalidatingFetcher fetcher = new RevalidatingFetcher(status(200));

        assertThat(fetcher.metrics()).hasSize(2);
        Metric bytes = metric(fetcher, "jenesis.proxy.revalidation.bytes");
        assertThat(bytes.value()).isZero();
        assertThat(bytes.unit()).isEqualTo("bytes");
        assertThat(bytes.limit()).isPresent();                 // bounded by the byte ceiling
        Metric entries = metric(fetcher, "jenesis.proxy.revalidation.entries");
        assertThat(entries.value()).isZero();
        assertThat(entries.limit()).isEmpty();                 // bounded by bytes, not a fixed count
        assertThat(fetcher.taskStatuses()).isEmpty();
    }

    @Test
    void the_revalidation_gauges_grow_as_an_index_is_remembered() throws IOException {
        byte[] body = "index-body-contents".getBytes(StandardCharsets.UTF_8);
        RevalidatingFetcher fetcher = new RevalidatingFetcher(validated("\"v1\"", body));

        fetcher.fetch(INDEX, Map.of());

        assertThat(metric(fetcher, "jenesis.proxy.revalidation.bytes").value()).isEqualTo((double) body.length);
        assertThat(metric(fetcher, "jenesis.proxy.revalidation.entries").value()).isEqualTo(1.0);
    }

    @Test
    void the_signals_collect_into_the_report_the_consumers_read() throws IOException {
        NegativeCachingFetcher negative = new NegativeCachingFetcher(status(404), Duration.ofSeconds(60));
        negative.fetch(URL, Map.of());
        RevalidatingFetcher revalidating = new RevalidatingFetcher(validated("\"v1\"", "body".getBytes(StandardCharsets.UTF_8)));
        revalidating.fetch(INDEX, Map.of());

        ObservabilityReport report = ObservabilityReport.from(List.of(negative, revalidating));

        assertThat(report.metrics()).extracting(Metric::name).containsExactly(
                "jenesis.proxy.negativecache.entries",
                "jenesis.proxy.revalidation.bytes",
                "jenesis.proxy.revalidation.entries");         // name-sorted, one collected view
        assertThat(report.healthChecks()).extracting(HealthCheck::name).containsExactly(
                "jenesis.proxy.negativecache",
                "jenesis.proxy.revalidation");
        assertThat(report.healthChecks()).extracting(HealthCheck::status).containsOnly(Health.UP);
        assertThat(report.healthChecks()).extracting(HealthCheck::description).allSatisfy(
                description -> assertThat(description).isNotBlank());
        assertThat(report.tasks()).isEmpty();
        assertThat(report.overall()).isEqualTo(Health.UP);
    }

    @Test
    void every_signal_name_follows_the_jenesis_proxy_grammar() {
        List<NegativeCachingFetcher> negatives = List.of(new NegativeCachingFetcher(status(404), Duration.ofSeconds(60)));
        RevalidatingFetcher revalidating = new RevalidatingFetcher(status(200));

        ObservabilityReport report = ObservabilityReport.from(
                List.of(negatives.get(0), revalidating));
        assertThat(report.metrics()).extracting(Metric::name)
                .allSatisfy(name -> assertThat(name).matches("jenesis\\.proxy\\..+"));
        assertThat(report.healthChecks()).extracting(HealthCheck::name)
                .allSatisfy(name -> assertThat(name).matches("jenesis\\.proxy\\..+"));
    }

    private static Metric metric(RevalidatingFetcher fetcher, String name) {
        return fetcher.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name));
    }

    /** A fetcher that always answers with the given status and an empty body (no validator, nothing to remember). */
    private static ProxyFormat.Fetcher status(int status) {
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                return Optional.of(new ProxyFormat.Fetched(status, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) {
                return Optional.of(new ProxyFormat.Download(status, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    /** A fetcher answering a 200 carrying an {@code ETag} and the given body, so the revalidation cache remembers it. */
    private static ProxyFormat.Fetcher validated(String etag, byte[] body) {
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                return Optional.of(new ProxyFormat.Fetched(200, body, Map.of("ETag", etag)));
            }
        };
    }
}
