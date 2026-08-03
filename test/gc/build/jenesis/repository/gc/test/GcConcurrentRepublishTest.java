package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mark-sweep collector's final data-safety guard against a dedup re-publish that re-references an orphan blob
 * while the collecting sweep is mid-flight: {@link Publication#link} clears the {@code gc/condemned/<hash>} marker on
 * its write path to un-condemn the re-linked blob, and the sweep must honour that clear right up to the destructive
 * delete. The sweep reads the marker to judge a blob, then makes a lease-fence manifest round-trip; a re-publish that
 * clears the marker in that window would be missed if the sweep deleted on the stale judgement, losing a blob a live
 * pointer now references. The collector re-reads the marker immediately before deleting, so a marker gone since the
 * judgement spares the blob. Driven over a real filesystem store wrapped to clear the marker at the exact instant the
 * sweep first reads it - the interleaving the write path documents. No network.
 */
class GcConcurrentRepublishTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
    }

    @Test
    void a_blob_re_referenced_by_a_concurrent_republish_mid_sweep_is_spared_not_deleted() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String orphan = publication.storeBlob(new ByteArrayInputStream("orphan".getBytes(StandardCharsets.UTF_8)));

        // Pass one condemns the orphan (writes gc/condemned/<orphan>); it is never deleted by the pass that judged it.
        assertThat(collector().collect(store, List.of("publish"), clock.instant()).condemned()).isEqualTo(1);
        assertThat(store.exists("gc/condemned/" + orphan)).isTrue();

        // Pass two would collect it - but a dedup re-publish clears the marker at the exact moment the sweep first
        // reads it (before its lease-fence round-trip and final re-read), the interleaving the write path un-condemns
        // through. The re-read must catch the now-absent marker and spare the re-referenced blob.
        MarkerClearingStore racing = new MarkerClearingStore(store, "gc/condemned/" + orphan);
        GcPlan second = collector().collect(racing, List.of("publish"), clock.instant());

        assertThat(second.collected()).as("the mid-sweep re-referenced blob is not collected").isZero();
        assertThat(store.exists("blobs/" + orphan))
                .as("a blob whose condemned marker a re-publish cleared mid-sweep is spared, not deleted").isTrue();
    }

    /** A store that, the first time the sweep reads the given marker key, deletes it after answering - simulating a
     *  concurrent {@link Publication#link} that re-referenced the blob and cleared its condemned marker in the window
     *  between the sweep's judgement read and its delete. Every other operation delegates to the real store. */
    private static final class MarkerClearingStore implements ArtifactStore {

        private final ArtifactStore delegate;
        private final String marker;
        private boolean armed = true;

        private MarkerClearingStore(ArtifactStore delegate, String marker) {
            this.delegate = delegate;
            this.marker = marker;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            Optional<Versioned> answer = delegate.readVersioned(key);
            if (armed && key.equals(marker) && answer.isPresent()) {
                armed = false;                 // clear it once, right after the sweep's judgement read - the re-publish race
                delegate.delete(key);
            }
            return answer;
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
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
