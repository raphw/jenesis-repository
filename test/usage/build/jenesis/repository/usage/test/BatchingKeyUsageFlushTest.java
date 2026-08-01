package build.jenesis.repository.usage.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.usage.BatchingKeyUsageTracker;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flush contract of the batching key-usage tracker: when {@link Authorization#recordUsed} returns {@code false}
 * (every compare-and-set lost to contention), the tracker must leave the credential's {@code flushed} watermark
 * unadvanced so the same delta is re-attempted on the next drain, rather than marking it written and silently dropping
 * the increment. Driven synchronously through {@link BatchingKeyUsageTracker#drain} over a real filesystem-backed
 * authorization whose conditional writes are forced to conflict once, then allowed to settle.
 */
class BatchingKeyUsageFlushTest {

    @TempDir
    Path root;

    private ConflictingWrites store;
    private Authorization authorization;
    private String hash;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore backend = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        store = new ConflictingWrites(backend);
        authorization = Authorization.enforcing(store);
        hash = Authorization.hash(Authorization.mint("acme"));
        authorization.provision("acme", hash, "k", null);
    }

    @Test
    void a_contended_flush_keeps_the_delta_and_re_applies_it_on_the_next_drain() throws IOException {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);
        Instant when = Instant.parse("2026-06-30T08:00:00Z");

        // First drain: every conditional write conflicts, so recordUsed returns false and the delta is NOT persisted.
        store.failWrites = true;
        tracker.drain(List.of(new BatchingKeyUsageTracker.Hit("acme", hash, "10.0.0.1")), when);

        assertThat(authorization.credential("acme", hash).orElseThrow().useCount())
                .as("a contended flush persists nothing").isZero();
        assertThat(tracker.tracked())
                .as("the credential is retained with its unflushed delta, not marked written and dropped").isEqualTo(1);

        // Second drain (same UTC day, no new hit): the retained delta must be re-attempted, and now settles.
        store.failWrites = false;
        tracker.drain(List.of(), when);

        assertThat(authorization.credential("acme", hash).orElseThrow().useCount())
                .as("the same delta is re-flushed on the next drain rather than being lost").isEqualTo(1L);
    }

    /** A store that forwards to a real backend but can be made to fail every conditional write (return {@code false},
     *  as a lost compare-and-set does), so {@link Authorization#recordUsed} exhausts its retries and forfeits. */
    private static final class ConflictingWrites implements ArtifactStore {
        private final ArtifactStore delegate;
        private volatile boolean failWrites;

        private ConflictingWrites(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (failWrites) {
                return false;   // every compare-and-set loses to contention
            }
            return delegate.writeVersioned(key, content, expected);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
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
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
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

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }
    }
}
