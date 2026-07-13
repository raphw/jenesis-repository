package build.jenesis.repository.store.test;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.QuotaArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A capped {@link QuotaArtifactStore} is its own {@link build.jenesis.repository.observation.ObservabilitySource}: it
 * reports {@code jenesis.quota.used} as a bounded gauge (used vs the configured ceiling, so the overview shows data
 * used vs available) and a {@code jenesis.quota.capacity} health check that goes DEGRADED once usage reaches the limit
 * and UNKNOWN when the counter cannot be read; an <em>unlimited</em> store reports nothing at all and runs no
 * background task, so it carries no {@code TaskStatus}. The signals collect into the single
 * {@link ObservabilityReport} view the distribution, Actuator and the docs all read, exercised against a real
 * filesystem store without the server, Micrometer or a network.
 */
class QuotaArtifactStoreObservabilityTest {

    @TempDir
    Path root;

    private ArtifactStore delegate() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(int length) {
        return new ByteArrayInputStream(new byte[length]);
    }

    @Test
    void an_unlimited_store_reports_nothing() {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 0);

        assertThat(store.metrics()).as("a non-positive limit is a transparent pass-through").isEmpty();
        assertThat(store.healthChecks()).isEmpty();
        assertThat(store.taskStatuses()).as("no background task of its own").isEmpty();
        assertThat(ObservabilityReport.from(List.of(store)).overall()).isEqualTo(Health.UP);
    }

    @Test
    void a_capped_store_reports_used_against_the_ceiling() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 1000);
        store.write("publish/maven/x", bytes(500));   // a pointer is not a blob and does not count
        store.write("blobs/aaa", bytes(250));

        Metric used = metric(store, "jenesis.quota.used");
        assertThat(used.kind()).isEqualTo(Metric.Kind.GAUGE);
        assertThat(used.value()).as("only blob bytes counted").isEqualTo(250.0);
        assertThat(used.limit()).as("the configured ceiling").hasValue(1000.0);
        assertThat(used.usage()).as("used vs available").hasValue(0.25);
        assertThat(used.unit()).isEqualTo("bytes");
    }

    @Test
    void a_capped_store_below_the_ceiling_is_up() {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 1000);

        assertThat(store.healthChecks()).singleElement().satisfies(check -> {
            assertThat(check.name()).isEqualTo("jenesis.quota.capacity");
            assertThat(check.status()).as("fresh store has headroom").isEqualTo(Health.UP);
        });
    }

    @Test
    void reaching_the_ceiling_degrades_the_capacity_check() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 500);
        store.write("blobs/aaa", bytes(500));

        assertThat(store.healthChecks()).singleElement().satisfies(check -> {
            assertThat(check.status()).as("at the limit a new blob is refused").isEqualTo(Health.DEGRADED);
            assertThat(check.detail()).isNotBlank();
        });
        assertThat(ObservabilityReport.from(List.of(store)).overall()).isEqualTo(Health.DEGRADED);
    }

    @Test
    void an_unreadable_counter_yields_unknown_and_suppresses_the_metric() {
        QuotaArtifactStore store = new QuotaArtifactStore(new ThrowingMeter(delegate()), 1000);

        assertThat(store.metrics()).as("no misleading zero when usage is unknown").isEmpty();
        assertThat(store.healthChecks()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo(Health.UNKNOWN);
            assertThat(check.detail()).contains("could not be read");
        });
    }

    @Test
    void the_signals_collect_into_the_report_and_follow_the_feature_grammar() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 1000);
        store.write("blobs/aaa", bytes(200));

        ObservabilityReport report = ObservabilityReport.from(List.of(store));

        assertThat(report.metrics()).extracting(Metric::name).containsExactly("jenesis.quota.used");
        assertThat(report.healthChecks()).extracting(HealthCheck::name).containsExactly("jenesis.quota.capacity");
        assertThat(report.metrics()).allSatisfy(metric -> {
            assertThat(metric.name()).matches("jenesis\\.quota\\..+");
            assertThat(metric.description()).isNotBlank();
        });
        assertThat(report.tasks()).as("a quota store runs no background task").isEmpty();
    }

    private static Metric metric(QuotaArtifactStore store, String name) {
        return store.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name));
    }

    /** A store that fails the versioned read the quota counter is kept on, to drive the counter-unreadable branch. */
    private record ThrowingMeter(ArtifactStore delegate) implements ArtifactStore {

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            throw new IOException("counter read failed");
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }
    }
}
