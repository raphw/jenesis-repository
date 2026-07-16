package build.jenesis.repository.gc.store;

import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;

import module java.base;

/**
 * The reference {@link GarbageCollector}, riding the shared artifact walk - never its own listing loop - so both
 * of its enumerations are ordered, resumable, segmented and multi-node-safe, and no phase ever holds the whole
 * store in memory:
 *
 * <p><b>Mark, sharded.</b> One walk pass ({@code gc-mark}) over the caller's pointer roots reads each small leaf
 * object and keeps every hash it names, buffered in memory only up to the walk's checkpoint stride: the walk
 * flushes the buffer <em>before</em> every cursor commit ({@code KeyVisitor.beforeCheckpoint}), so a resume can
 * never skip a pointer whose reference was lost with a crashed buffer - the guard on the absolute invariant that
 * a referenced blob is never deleted. Flushed references land as immutable, append-only batch objects
 * {@code gc/<pass>/refs/<hh>/<collector>-<n>} sharded by the hash's leading byte (never a read-modify-write, so
 * concurrent workers and crash-replays only ever add duplicate observations, which union away). Once the mark
 * pass completes, its shards are complete for every live pointer: every segment was fully walked by whoever
 * finished it, so a straggler's late flush can only add redundancy.
 *
 * <p><b>Condemn-then-collect, across two consecutive passes.</b> A second walk pass ({@code gc-sweep}) streams the
 * flat {@code blobs/} namespace in hash order - so the current {@code <hh>} shard's references are the only set in
 * memory, O(N/256) - and judges each blob against the completed mark: a referenced blob has any stale
 * {@code gc/condemned/<hash>} marker removed; an unreferenced one is <em>condemned</em> (marker created, stamped
 * with this pass) the first time and <em>deleted only when its marker carries an earlier pass</em> - the marker is
 * the clock, giving every crash-torn or in-flight publish at least one full collection interval of grace with no
 * store-timestamp API. The marker re-read immediately before deletion is the final guard: a dedup re-publish that
 * re-links condemned content clears the marker on the write path ({@code Publication.link}), collapsing the
 * residual race to the two back-to-back reads between that re-read and the delete. Blob first, marker last; a
 * marker whose blob is gone is swept by the convergence leg, which also drops the reference shards of superseded
 * passes.
 *
 * <p>Both phases only ever act on shapes they recognise: a leaf that is not a 64-hex SHA-256 pointer is skipped at
 * mark, and a {@code blobs/} name that is not a hash is never judged, let alone deleted.
 */
public final class MarkSweepGarbageCollector implements GarbageCollector {

    /** The two walk consumers, whose pass state a console reads back through {@code ArtifactWalk.pass}. */
    static final String MARK = "gc-mark", SWEEP = "gc-sweep";

    private static final String CONDEMNED = "gc/condemned";

    /** Names fetched per {@link ArtifactStore#page} call when streaming the marker space. */
    private static final int PAGE = 1000;

    /** A pointer names a hash in a few dozen bytes; a larger leaf is other metadata and is never read whole. */
    private static final int LARGEST_POINTER = 1024;

    private final ArtifactWalk walk;

    /** This collector's identity inside reference-batch names, so concurrent collectors never contend on a key. */
    private final String collector = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong batches = new AtomicLong();

    public MarkSweepGarbageCollector(ArtifactWalk walk) {
        this.walk = walk;
    }

    @Override
    public GcPlan plan(ArtifactStore store, List<String> pointerRoots, Instant now) throws IOException {
        roots(pointerRoots);
        Optional<WalkPass> mark = walk.pass(store, MARK);
        long judged = mark.isEmpty() ? 0
                : mark.get().complete() ? mark.get().generation() : mark.get().generation() - 1;
        if (judged <= 0) {
            return new GcPlan(false, 0, 0, 0, List.of()); // no completed mark ever ran - nothing is due yet
        }
        References references = new References(store, judged);
        long[] due = {0};
        List<String> sample = new ArrayList<>();
        each(store, CONDEMNED, name -> {
            if (!hash(name) || marked(store, name, judged)
                    || !store.exists("blobs/" + name) || references.contains(name)) {
                return; // unrecognised, condemned by a newer judgment, already-collected residue, or re-referenced
            }
            due[0]++;
            if (sample.size() < GcPlan.SAMPLE) {
                sample.add(name);
            }
        });
        return new GcPlan(true, 0, 0, due[0], sample);
    }

    /** Whether the marker was stamped by a judgment newer than the completed mark - due only at the pass after
     *  the one that condemned it. An unreadable marker is treated as newer: never due, repaired by a sweep. */
    private static boolean marked(ArtifactStore store, String name, long judged) throws IOException {
        Marker marker = store.readVersioned(CONDEMNED + "/" + name)
                .map(MarkSweepGarbageCollector::parse).orElse(null);
        return marker == null || marker.pass() > judged;
    }

    @Override
    public GcPlan collect(ArtifactStore store, List<String> pointerRoots, Instant now) throws IOException {
        WalkPass marked = walk.walk(store, MARK, roots(pointerRoots), new Mark(store));
        if (!marked.complete()) {
            // Another node still holds mark segments: the reference shards are not yet complete, and judging
            // blobs against an incomplete mark could condemn (though never delete) everything it missed. Report
            // the partial pass and let the next interval - or the node that finishes - do the judging.
            return new GcPlan(false, 0, 0, 0, List.of());
        }
        Sweep sweep = new Sweep(store, marked.generation(), now);
        WalkPass swept = walk.walk(store, SWEEP, List.of("blobs"), sweep);
        if (!swept.complete()) {
            return new GcPlan(false, sweep.condemned, sweep.spared, sweep.collected, sweep.sample);
        }
        converge(store, marked.generation());
        return new GcPlan(true, sweep.condemned, sweep.spared, sweep.collected, sweep.sample);
    }

    /** The bookkeeping convergence after a completed sweep: a marker whose blob is gone (the residue of a crash
     *  between the blob and marker deletes, or of an already-collected blob) is removed, and the reference shards
     *  of every superseded pass are dropped - so the {@code gc/} space converges instead of growing forever, and
     *  an idempotent re-run over a converged store changes nothing. */
    private void converge(ArtifactStore store, long generation) throws IOException {
        each(store, CONDEMNED, name -> {
            if (hash(name) && !store.exists("blobs/" + name)) {
                deleteIfPresent(store, CONDEMNED + "/" + name);
            }
        });
        for (String child : store.list("gc")) {
            long pass;
            try {
                pass = Long.parseLong(child);
            } catch (NumberFormatException _) {
                continue; // the condemned space and anything unrecognised stay
            }
            if (pass < generation) {
                drop(store, "gc/" + child);
            }
        }
    }

    /** Delete a whole bookkeeping subtree (a superseded pass's reference batches - bounded, never artifacts). */
    private static void drop(ArtifactStore store, String prefix) throws IOException {
        if (store.exists(prefix)) {
            store.delete(prefix);
            return;
        }
        for (String child : store.list(prefix)) {
            drop(store, prefix + "/" + child);
        }
    }

    /** Validate and normalise the caller's pointer roots: at least one, and never one of the store namespaces the
     *  collector itself owns or judges - marking {@code blobs} as a pointer root is a caller bug, not a layout. */
    private static List<String> roots(List<String> pointerRoots) {
        if (pointerRoots == null || pointerRoots.isEmpty()) {
            throw new IllegalArgumentException("garbage collection needs at least one pointer root, e.g. publish");
        }
        List<String> roots = pointerRoots.stream().distinct().sorted().toList();
        for (String root : roots) {
            if (root == null || root.isBlank() || root.equals("blobs") || root.equals("gc") || root.equals("walks")) {
                throw new IllegalArgumentException("not a pointer root: " + root);
            }
        }
        return roots;
    }

    /** The mark phase's visitor: buffer every hash a pointer leaf names, flushed as append-only batch objects
     *  before each walk checkpoint - so no committed cursor ever lies about an unflushed reference. */
    private final class Mark implements ArtifactWalk.KeyVisitor {

        private final ArtifactStore store;
        private final Map<String, List<String>> buffer = new HashMap<>(); // leading hash byte -> hashes to flush
        private long generation;

        private Mark(ArtifactStore store) {
            this.store = store;
        }

        @Override
        public void visit(String key) throws IOException {
            long size = store.size(key);
            if (size < 0 || size > LARGEST_POINTER) {
                return;
            }
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
            if (pointer.isEmpty()) {
                return; // removed between the walk's listing and this read - nothing references through it
            }
            String named = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
            if (hash(named)) {
                buffer.computeIfAbsent(named.substring(0, 2), _ -> new ArrayList<>()).add(named);
            }
        }

        @Override
        public void beforeCheckpoint(String cursor) throws IOException {
            if (buffer.isEmpty()) {
                return;
            }
            if (generation == 0) {
                // The pass this worker is contributing to; read lazily since it exists only once the walk began.
                // Should the claim have been reclaimed and the manifest turned over, references land in the newer
                // pass's shards - a stale-but-true observation that can only ever spare a blob, never condemn one.
                generation = walk.pass(store, MARK).map(WalkPass::generation)
                        .orElseThrow(() -> new IOException("no mark pass to record references under"));
            }
            for (Map.Entry<String, List<String>> shard : buffer.entrySet()) {
                byte[] content = String.join("\n", shard.getValue()).getBytes(StandardCharsets.UTF_8);
                for (int attempt = 0; true; attempt++) {
                    String key = "gc/" + generation + "/refs/" + shard.getKey()
                            + "/" + collector + "-" + batches.incrementAndGet();
                    if (store.writeVersioned(key, content, null)) {
                        break; // create-if-absent under a collector-unique name: a collision is one in a billion
                    }
                    if (attempt == 2) {
                        throw new IOException("could not record a reference batch under " + key);
                    }
                }
            }
            buffer.clear();
        }
    }

    /** The sweep phase's visitor: judge each blob, in hash order, against the completed mark's shards. */
    private final class Sweep implements ArtifactWalk.KeyVisitor {

        private final ArtifactStore store;
        private final long generation;
        private final Instant now;
        private final References references;
        private long condemned, spared, collected;
        private final List<String> sample = new ArrayList<>();

        private Sweep(ArtifactStore store, long generation, Instant now) {
            this.store = store;
            this.generation = generation;
            this.now = now;
            this.references = new References(store, generation);
        }

        @Override
        public void visit(String key) throws IOException {
            if (!key.startsWith("blobs/")) {
                return;
            }
            String hash = key.substring("blobs/".length());
            if (!hash(hash)) {
                return; // only content-addressed objects are ever judged, let alone deleted
            }
            String marker = CONDEMNED + "/" + hash;
            if (references.contains(hash)) {
                if (deleteIfPresent(store, marker)) {
                    spared++; // referenced again - the dedup re-publish an earlier pass condemned
                }
                return;
            }
            Optional<ArtifactStore.Versioned> current = store.readVersioned(marker);
            Marker parsed = current.map(MarkSweepGarbageCollector::parse).orElse(null);
            if (parsed == null) {
                // Unreferenced but not (recognisably) condemned yet: condemn it now, never delete it in the pass
                // that first judged it. Create-if-absent (an unreadable marker is repaired on its own token); a
                // lost race means a concurrent sweeper condemned it, which is convergence, not a lost update.
                var _ = store.writeVersioned(marker, marker(generation, now),
                        current.map(ArtifactStore.Versioned::token).orElse(null));
                condemned++;
            } else if (parsed.pass() < generation) {
                // Condemned by an earlier pass and still unreferenced by this one. The marker read above is the
                // final guard - a re-link cleared it on the write path - and the completed mark's shard needs no
                // re-read: it gained nothing but duplicates since the pass finished. Blob first, marker last, so
                // a crash in between leaves only a marker the convergence leg removes.
                deleteIfPresent(store, key);
                deleteIfPresent(store, marker);
                collected++;
                if (sample.size() < GcPlan.SAMPLE) {
                    sample.add(hash);
                }
            }
            // parsed.pass() >= generation: condemned within this judgment - its grace interval is still running.
        }
    }

    /** The completed mark's reference shards, loaded one leading-byte shard at a time - both consumers stream
     *  hashes in name order, so this is a sequential read of at most 256 shards, never an O(N) set. */
    private static final class References {

        private final ArtifactStore store;
        private final long generation;
        private String shard;
        private Set<String> hashes = Set.of();

        private References(ArtifactStore store, long generation) {
            this.store = store;
            this.generation = generation;
        }

        private boolean contains(String hash) throws IOException {
            String leading = hash.substring(0, 2);
            if (!leading.equals(shard)) {
                shard = leading;
                hashes = load(leading);
            }
            return hashes.contains(hash);
        }

        private Set<String> load(String leading) throws IOException {
            Set<String> loaded = new HashSet<>();
            String prefix = "gc/" + generation + "/refs/" + leading;
            for (String batch : store.list(prefix)) {
                Optional<ArtifactStore.Versioned> content = store.readVersioned(prefix + "/" + batch);
                if (content.isPresent()) {
                    for (String line : new String(content.get().content(), StandardCharsets.UTF_8).split("\n")) {
                        if (!line.isBlank()) {
                            loaded.add(line.trim());
                        }
                    }
                }
            }
            return loaded;
        }
    }

    /** A condemned marker's content: the pass whose judgment condemned the blob (the clock the grace interval is
     *  measured in) and when - the {@code since} a console shows, never consulted for correctness. */
    private record Marker(long pass, Instant since) {
    }

    private static byte[] marker(long pass, Instant since) {
        return ("pass=" + pass + "\nsince=" + since).getBytes(StandardCharsets.UTF_8);
    }

    /** Parse a marker; {@code null} for an unreadable one, which is re-stamped rather than trusted. */
    private static Marker parse(ArtifactStore.Versioned versioned) {
        try {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(versioned.content()));
            return new Marker(Long.parseLong(properties.getProperty("pass")),
                    Instant.parse(properties.getProperty("since")));
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private static boolean deleteIfPresent(ArtifactStore store, String key) throws IOException {
        if (!store.exists(key)) {
            return false;
        }
        store.delete(key);
        return true;
    }

    /** Whether a value is a bare SHA-256 - the only shape the collector ever trusts as naming a blob. */
    private static boolean hash(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    private interface NameAction {
        void accept(String name) throws IOException;
    }

    /** Stream every immediate child name under {@code prefix} through {@code action}, paged - never one list. */
    private static void each(ArtifactStore store, String prefix, NameAction action) throws IOException {
        String after = "";
        while (true) {
            List<String> names = new ArrayList<>();
            store.page(prefix, after, PAGE, names::add);
            for (String name : names) {
                action.accept(name);
            }
            if (names.size() < PAGE) {
                return;
            }
            after = names.getLast();
        }
    }
}
