package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DirtyIndexFeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reusable incremental-derived-index primitive ({@link DirtyIndexFeed}) exercised as a standalone dirty-set feed
 * over a real {@code FilesystemArtifactStore}, driving a tiny in-test "index" (a coordinate&rarr;version map that stands
 * in for the Lucene snapshot the search index actually maintains). Each test pins one property of the WIDX.1 change-fed
 * discipline at the primitive level: a marked add / update / delete is reflected after a sweep <em>without</em> a full
 * rebuild; the feed compacts (coalesces re-marks, clears applied markers); the reconcile backstop heals a change the
 * feed missed; an out-of-order marker never regresses a newer document; a crash between snapshot-commit and marker-clear
 * replays safely (idempotent upsert); and the sweep is provably O(&Delta;) - one publish is one applier call and one
 * {@code dirty/} listing, never a walk of the whole coordinate set - shown with the {@link CountingStore} spy.
 */
class DirtyIndexFeedTest {

    @TempDir
    Path root;

    private CountingStore store;
    private DirtyIndexFeed feed;

    /** The derived index the sweep maintains: coordinate to the version currently indexed for it. Stands in for the
     *  search index's per-coordinate document (an upsert sets the version, a delete removes the entry). */
    private final NavigableMap<String, Long> index = new TreeMap<>();

    @BeforeEach
    void setUp() {
        store = new CountingStore(ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null));
        feed = new DirtyIndexFeed(store, "index/search");
    }

    /** The caller's apply step: idempotent upsert-by-coordinate with the out-of-order guard - skip a marker whose
     *  version is older than what is already indexed for that coordinate, so a stale event never regresses a newer
     *  document. Records every invocation so a test can prove O(&Delta;). */
    private final AtomicInteger applierCalls = new AtomicInteger();

    private DirtyIndexFeed.Applier upsert() {
        return entry -> {
            Long indexed = index.get(entry.coordinate());
            if (indexed != null && entry.version() < indexed) {
                return false; // out-of-order guard: do not regress a newer document, leave the marker for later
            }
            applierCalls.incrementAndGet();
            if (entry.removed()) {
                index.remove(entry.coordinate());
            } else {
                index.put(entry.coordinate(), entry.version());
            }
            return true;
        };
    }

    /** One full incremental sweep: apply the dirty set into the index, then (snapshot "committed") clear what applied. */
    private void sweep() throws IOException {
        List<DirtyIndexFeed.Entry> applied = feed.applySince(upsert());
        feed.clear(applied);
    }

    @Test
    void an_add_update_and_delete_are_reflected_after_a_sweep_without_a_full_rebuild() throws IOException {
        feed.touched("com.acme:app", 100);
        sweep();
        assertThat(index).containsEntry("com.acme:app", 100L);

        feed.touched("com.acme:app", 200); // an update at a newer version
        sweep();
        assertThat(index).as("the update upserts by coordinate").containsEntry("com.acme:app", 200L);

        feed.removed("com.acme:app", 300);
        sweep();
        assertThat(index).as("the delete removes the document").doesNotContainKey("com.acme:app");

        assertThat(store.listsOutside("index/search/dirty"))
                .as("no sweep ever enumerated anything but the dirty/ feed - never a full rebuild").isZero();
    }

    @Test
    void the_feed_coalesces_remarks_and_clears_applied_markers() throws IOException {
        feed.touched("a", 5);
        feed.touched("a", 3); // an older stamp must not overwrite the newer marker
        feed.touched("a", 7); // a newer stamp coalesces onto the one marker
        feed.touched("b", 1);

        List<DirtyIndexFeed.Entry> pending = feed.pending();
        assertThat(pending).as("re-marks coalesce onto one marker per coordinate").hasSize(2);
        assertThat(pending.stream().filter(e -> e.coordinate().equals("a")).findFirst().orElseThrow().version())
                .as("coalescing keeps the newest version").isEqualTo(7L);

        sweep();
        assertThat(feed.pending()).as("applied markers are cleared - the feed stays bounded").isEmpty();
        assertThat(index).containsOnlyKeys("a", "b");
    }

    @Test
    void the_reconcile_backstop_heals_a_change_the_feed_missed() throws IOException {
        // Two coordinates published through the feed, and one "imported" straight into truth, bypassing the observer -
        // the classic missed event the live feed can never see.
        feed.touched("seen:1", 10);
        feed.touched("seen:2", 20);
        sweep();
        NavigableMap<String, Long> truth = new TreeMap<>(Map.of("seen:1", 10L, "seen:2", 20L, "imported:9", 90L));
        assertThat(index).as("the imported coordinate is missing - the feed never carried it").doesNotContainKey("imported:9");

        // The periodic reconcile: capture a cutoff, rebuild the index from durable truth, then GC the feed through the
        // cutoff. The rebuild heals the drift; the feed compaction bounds the feed.
        long cutoff = 100;
        index.clear();
        index.putAll(truth);
        feed.compactThrough(cutoff);

        assertThat(index).as("the reconcile rebuild from truth heals the missed event").containsKey("imported:9");
        assertThat(feed.pending()).as("the reconcile GCs the feed through its cutoff").isEmpty();
    }

    @Test
    void an_out_of_order_event_does_not_regress_a_newer_document() throws IOException {
        feed.touched("x", 200);
        sweep();
        assertThat(index).containsEntry("x", 200L);

        // A stale event for x at an older version arrives late (e.g. a slow observer overtaken by a newer publish).
        feed.touched("x", 100);
        List<DirtyIndexFeed.Entry> applied = feed.applySince(upsert());

        assertThat(applied).as("the applier skipped the stale marker, so nothing is cleared for it").isEmpty();
        assertThat(index).as("the newer document is not regressed").containsEntry("x", 200L);
        assertThat(feed.pending()).as("the skipped marker is left in the feed, not silently dropped").hasSize(1);
    }

    @Test
    void a_crash_between_commit_and_clear_replays_safely() throws IOException {
        feed.touched("c", 50);

        // Sweep 1: apply into the index (the snapshot "commits") but crash before clearing the markers.
        List<DirtyIndexFeed.Entry> applied = feed.applySince(upsert());
        assertThat(index).containsEntry("c", 50L);
        assertThat(applied).hasSize(1);
        // ... crash here: feed.clear(applied) never runs, so the marker is still pending.
        assertThat(feed.pending()).as("the un-cleared marker survives the crash").hasSize(1);

        int callsBefore = applierCalls.get();

        // Sweep 2 (recovery): the marker replays. The idempotent upsert absorbs it - same result, no corruption.
        sweep();
        assertThat(index).as("replay is a no-op on the already-applied document").containsEntry("c", 50L);
        assertThat(applierCalls.get()).as("the replay did re-apply the surviving marker").isEqualTo(callsBefore + 1);
        assertThat(feed.pending()).as("after the successful sweep the marker is finally cleared").isEmpty();
    }

    @Test
    void clear_leaves_a_marker_re_touched_during_the_sweep() throws IOException {
        feed.touched("d", 10);
        List<DirtyIndexFeed.Entry> applied = feed.applySince(upsert()); // read + apply the v10 marker

        // A concurrent write re-touches d at a newer version after it was read but before it is cleared.
        feed.touched("d", 20);

        feed.clear(applied); // must NOT drop the newer marker - its token changed
        assertThat(feed.pending()).as("the re-touch survives the clear to be applied next sweep").hasSize(1);
        assertThat(feed.pending().getFirst().version()).isEqualTo(20L);
    }

    @Test
    void compact_through_keeps_markers_newer_than_the_cutoff() throws IOException {
        feed.touched("old", 10);
        feed.touched("new", 30);

        feed.compactThrough(20);

        List<DirtyIndexFeed.Entry> pending = feed.pending();
        assertThat(pending).as("only markers at or before the cutoff are GC'd").hasSize(1);
        assertThat(pending.getFirst().coordinate()).isEqualTo("new");
    }

    @Test
    void a_sweep_after_one_publish_is_o_of_delta_not_o_of_n() throws IOException {
        // A large existing index - N documents already indexed (seeded directly, as a bootstrapped snapshot would be).
        for (int i = 0; i < 1000; i++) {
            index.put("pkg:" + i, 1L);
        }

        // One publish marks exactly one coordinate dirty.
        feed.touched("pkg:new", 2);

        applierCalls.set(0);
        store.reset();
        sweep();

        assertThat(applierCalls.get())
                .as("O(Delta): the sweep applied exactly the one changed coordinate, not the N indexed ones")
                .isEqualTo(1);
        assertThat(store.listCalls("index/search/dirty"))
                .as("the sweep listed only the dirty/ feed").isEqualTo(1);
        assertThat(store.listsOutside("index/search/dirty"))
                .as("O(Delta): the sweep never enumerated the whole coordinate set").isZero();
        assertThat(store.readVersionedCalls())
                .as("O(Delta): one marker read (pending) plus one token re-read (clear), not N")
                .isEqualTo(2);
        assertThat(index).containsEntry("pkg:new", 2L).hasSize(1001);
    }

    @Test
    void a_removed_marker_newer_than_a_pending_upsert_supersedes_it_before_a_sweep() throws IOException {
        // The coalesce rule for a mixed op: a delete stamped newer than a pending upsert on the same coordinate wins,
        // so the sweep applies a delete-by-coordinate rather than the superseded upsert - the removed flag rides the
        // higher version. (The upsert-then-newer-delete direction, distinct from the delete-then-newer-upsert one.)
        feed.touched("com.acme:app", 100);
        feed.removed("com.acme:app", 200);

        List<DirtyIndexFeed.Entry> pending = feed.pending();
        assertThat(pending).as("one coalesced marker, not two").hasSize(1);
        assertThat(pending.getFirst().removed()).as("the newer delete supersedes the pending upsert").isTrue();
        assertThat(pending.getFirst().version()).isEqualTo(200L);

        index.put("com.acme:app", 100L);
        sweep();
        assertThat(index).as("the sweep applied the delete, not the superseded upsert").doesNotContainKey("com.acme:app");
    }

    @Test
    void a_feed_needs_a_non_empty_prefix_and_a_store() {
        assertThatThrownBy(() -> new DirtyIndexFeed(null, "index/search"))
                .as("the store is required").isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DirtyIndexFeed(store, null))
                .as("the prefix is required").isInstanceOf(NullPointerException.class);
        // An empty or slash-only prefix strips to nothing: the feed would live at the store root, colliding with every
        // other feed, so it is rejected at construction rather than silently mis-scoped. (The strip trims surrounding
        // slashes, not whitespace, so a non-slash prefix is taken verbatim.)
        for (String blank : List.of("", "/", "///")) {
            assertThatThrownBy(() -> new DirtyIndexFeed(store, blank))
                    .as("a prefix that strips to nothing is refused: '" + blank + "'")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-empty prefix");
        }
    }

    @Test
    void a_prefix_is_stripped_of_surrounding_slashes_so_equivalent_prefixes_coincide() throws IOException {
        // "index/search", "/index/search" and "index/search/" all name the one feed: the slashes are trimmed, so a
        // marker written through one handle is seen through another - a mis-slashed prefix never forks a second feed.
        DirtyIndexFeed slashed = new DirtyIndexFeed(store, "/index/search/");
        slashed.touched("x", 1);
        assertThat(feed.pending()).as("the trimmed prefix names the same feed the bare one does").hasSize(1);
    }

    @Test
    void a_marker_write_that_cannot_land_surfaces_rather_than_silently_dropping() throws IOException {
        // A marker write races only other writers touching the SAME coordinate, so contention is bounded; but if the
        // compare-and-set never lands, the mark must fail loudly (an IOException the write path hears about) rather
        // than silently drop a change the derived index would then never see until the next full reconcile.
        DirtyIndexFeed contended = new DirtyIndexFeed(new AlwaysConflictingStore(store), "index/search");
        assertThatThrownBy(() -> contended.touched("com.acme:app", 100))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("could not record dirty marker");
    }

    /** Forwards to a real store but reports every versioned write as a compare-and-set conflict, so a test can drive
     *  the marker write's exhausted-attempts path (the loud failure a persistently contended mark takes). */
    private record AlwaysConflictingStore(ArtifactStore delegate) implements ArtifactStore {

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            return false;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
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
    }

    /**
     * An {@link ArtifactStore} decorator that counts the enumeration and small-object reads a sweep issues, so a test
     * can prove the sweep's cost is O(&Delta;) - proportional to the dirty set - and never walks the whole coordinate
     * set. Every mutating and streaming method delegates untouched; only {@link #list}, {@link #page} and
     * {@link #readVersioned} are tallied.
     */
    private static final class CountingStore implements ArtifactStore {

        private final ArtifactStore delegate;
        private final Map<String, Integer> lists = new HashMap<>();
        private int readVersioned;

        private CountingStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        void reset() {
            lists.clear();
            readVersioned = 0;
        }

        int listCalls(String prefix) {
            return lists.getOrDefault(prefix, 0);
        }

        int listsOutside(String prefix) {
            return lists.entrySet().stream()
                    .filter(e -> !e.getKey().equals(prefix))
                    .mapToInt(Map.Entry::getValue).sum();
        }

        int readVersionedCalls() {
            return readVersioned;
        }

        @Override
        public List<String> list(String prefix) {
            lists.merge(prefix, 1, Integer::sum);
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            lists.merge(prefix, 1, Integer::sum);
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            readVersioned++;
            return delegate.readVersioned(key);
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
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
