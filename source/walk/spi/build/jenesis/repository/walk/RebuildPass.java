package build.jenesis.repository.walk;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

import module java.base;

/**
 * The shared rebuild pass: one walk over the pointer roots feeding <em>every</em> {@link WalkConsumer} - so N
 * metadata rebuilders never mean N tree walks. This is the walk half of the two-route derived-metadata contract
 * made runnable: a scheduled surface resolves the walk, gathers {@link WalkConsumer#discovered()} and calls
 * {@link #run} on a cadence; steady-state freshness stays with the publication events
 * ({@code PublicationObserver.onPublished} / {@code onDeleted}), and this pass is the first-activation back-fill,
 * the periodic refresh and the self-heal - a consumer enabled late rebuilds its whole view from it.
 *
 * <p><b>What a consumer is handed.</b> Every leaf under the walked roots that is a serving pointer - a small object
 * naming a lower-case SHA-256 - is delivered as one {@link WalkConsumer#onRetained} call with the descriptor
 * richness this neutral site has: under the free core's own {@code publish/} namespace the descriptor's path is the
 * serving request path (exactly what {@code onPublished} / {@code onDeleted} carry); under any other pointer root
 * (a format's own blobs-namespace keys) the path is the raw store key, whose layout only the owning format knows -
 * a coordinate-needing consumer describes it through its format. The blob hash is always set; the size is the
 * stored blob's, or {@code -1} for a pointer whose blob is missing - delivered, not skipped, so a reconcile
 * consumer sees exactly the torn state it exists to repair. A leaf that names no hash (a sidecar row, a marker, an
 * index) is never delivered.
 *
 * <p><b>The withheld screen.</b> Under the free {@code publish/} namespace the pass yields exactly what a {@code GET}
 * would, applying the same withheld screen {@code PublishedAssets} does through {@code Publication.located}: the
 * quarantine review subtree ({@code publish/quarantine/...}) is stored but never served, so it is never delivered
 * (no phantom index entry for a held pointer); and a path a screen retracts after the fact (a
 * {@code PublishInterceptor.withheld} verdict against an artifact that has served for months) is skipped, so a
 * rebuild never reinstates a retracted-after-advisory artifact into a consumer's index. A torn pointer whose blob is
 * merely gone is <em>not</em> withheld - it is still delivered as the torn state a reconcile consumer repairs, so
 * only a path whose blob is present yet unlocatable is screened out. The screen is the {@code publish/} withhold
 * model's; a format's own blobs-namespace root carries no publication pointer and is delivered raw as before.
 *
 * <p><b>Delivery and failure.</b> The walk's contract carries over: every retained pointer is delivered exactly
 * once per pass, and at least once for the uncommitted stride tail after a crash-resume - consumers are idempotent.
 * A consumer failure propagates and stops this worker's segment with its claim left to expire; the pass then
 * resumes from the last committed cursor, so a failure delays a rebuild but never silently truncates it - a stuck
 * pass is visible through {@link ArtifactWalk#pass} / {@link ArtifactWalk#segments}, never a quietly-incomplete
 * view served as whole. {@link WalkConsumer#onPassStarted} fires on this worker before its first delivery (and
 * before {@code onPassCompleted} on an empty store - a rebuild from an empty truth is still a rebuild);
 * {@link WalkConsumer#onPassCompleted} fires when this worker observed the pass complete. The hooks are per-worker:
 * with one scheduled worker driving the pass - the default - a snapshot rebuilder sees the whole pass between its
 * hooks, while a deployment that fans {@code run} across threads or nodes keeps every streaming consumer correct
 * but must not drive a snapshot rebuilder this way (its accumulation would span workers) - the degrade-and-say-so
 * each such consumer records.
 */
public final class RebuildPass {

    /** The pass-state scope every joiner shares ({@code walks/rebuild/...}) - one pass, however many workers. */
    public static final String CONSUMER = "rebuild";

    /** A pointer names a hash in a few dozen bytes; a larger leaf is other metadata and is never read whole. */
    private static final int LARGEST_POINTER = 1024;

    private RebuildPass() {
    }

    /**
     * Join the shared rebuild pass over {@code pointerRoots} (the free {@code publish} namespace plus every
     * blobs-namespace root the caller's installed formats declare) and stream every retained pointer to every one
     * of {@code consumers}; empty when there is no consumer to feed - nothing is enumerated and no pass state is
     * touched. Returns the pass as this worker last saw it: {@code COMPLETE} when it just finished, {@code ACTIVE}
     * while other holders still own segments - re-invoke on the next cadence, or let another node finish.
     */
    public static Optional<WalkPass> run(ArtifactWalk walk, ArtifactStore store, List<String> pointerRoots,
                                         List<WalkConsumer> consumers) throws IOException {
        return run(walk, store, new Publication(store), pointerRoots, consumers);
    }

    /**
     * The explicit seam: join the shared rebuild pass reusing a {@link Publication} already constructed over the same
     * store rather than making a second, so the withheld screen over the {@code publish/} namespace runs the caller's
     * interceptor chain (the free edition's {@code ServiceLoader}-discovered chain is empty; a test or an embedder
     * injects one here) - the same seam {@code PublishedAssets} exposes for the same reason.
     */
    public static Optional<WalkPass> run(ArtifactWalk walk, ArtifactStore store, Publication publication,
                                         List<String> pointerRoots, List<WalkConsumer> consumers) throws IOException {
        if (consumers.isEmpty()) {
            return Optional.empty();
        }
        Delivery delivery = new Delivery(walk, store, publication, List.copyOf(consumers));
        WalkPass pass = walk.walk(store, CONSUMER, roots(pointerRoots), delivery);
        if (pass.complete()) {
            delivery.started(pass);
            for (WalkConsumer consumer : consumers) {
                consumer.onPassCompleted(pass);
            }
        }
        return Optional.of(pass);
    }

    /** Validate and normalise the caller's pointer roots: at least one, and never one of the store namespaces the
     *  walk or collector bookkeeping owns - walking {@code blobs} for pointers is a caller bug, not a layout. */
    private static List<String> roots(List<String> pointerRoots) {
        if (pointerRoots == null || pointerRoots.isEmpty()) {
            throw new IllegalArgumentException("a rebuild pass needs at least one pointer root, e.g. publish");
        }
        List<String> roots = pointerRoots.stream().distinct().sorted().toList();
        for (String root : roots) {
            if (root == null || root.isBlank() || root.equals("blobs") || root.equals("gc") || root.equals("walks")) {
                throw new IllegalArgumentException("not a pointer root: " + root);
            }
        }
        return roots;
    }

    /** Whether a pointer's content is a lower-case SHA-256 hex - the only leaf shape delivered as an artifact. */
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

    /** The pass's visitor: turn each pointer leaf into one descriptor and fan it out to every consumer, firing
     *  {@code onPassStarted} lazily before the first delivery - read from the live manifest, so the hook carries
     *  the generation actually running rather than a guess made before the walk began. */
    private static final class Delivery implements ArtifactWalk.KeyVisitor {

        private final ArtifactWalk walk;
        private final ArtifactStore store;
        private final Publication publication;
        private final List<WalkConsumer> consumers;
        private boolean started;

        private Delivery(ArtifactWalk walk, ArtifactStore store, Publication publication,
                         List<WalkConsumer> consumers) {
            this.walk = walk;
            this.store = store;
            this.publication = publication;
            this.consumers = consumers;
        }

        private void started(WalkPass pass) throws IOException {
            if (started) {
                return;
            }
            started = true;
            for (WalkConsumer consumer : consumers) {
                consumer.onPassStarted(pass);
            }
        }

        @Override
        public void visit(String key) throws IOException {
            long size = store.size(key);
            if (size < 0 || size > LARGEST_POINTER) {
                return;
            }
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
            if (pointer.isEmpty()) {
                return; // removed between the walk's listing and this read - nothing is served through it
            }
            String named = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
            if (!hash(named)) {
                return; // a sidecar row, marker or index - not a serving pointer, never delivered
            }
            String path = key.startsWith("publish/") ? key.substring("publish".length()) : key;
            if (key.startsWith("publish/") && withheld(path, named)) {
                return; // withheld from serving - a GET would 404 it, so a rebuild must not reinstate it into an index
            }
            if (!started) {
                started(walk.pass(store, CONSUMER)
                        .orElseThrow(() -> new IOException("no rebuild pass to deliver under")));
            }
            ArtifactDescriptor artifact = new ArtifactDescriptor(null, null, null, path, null, false, named,
                    store.size("blobs/" + named));
            for (WalkConsumer consumer : consumers) {
                consumer.onRetained(artifact, store);
            }
        }

        /** Whether the free {@code publish/} namespace withholds this request path from serving - the quarantine read
         *  side {@code PublishedAssets} screens through {@link Publication#located}, mirrored here so a rebuild never
         *  reinstates a withheld artifact into a consumer's index. The quarantine review subtree
         *  ({@code publish/quarantine/...}) is stored but never served, exactly as {@code PublishedAssets} never
         *  descends it; and a screen that retracts an already-linked path after the fact leaves {@code located} empty
         *  while its blob is still stored. A torn pointer whose blob is simply gone is not withheld - it is delivered
         *  as the torn state a reconcile consumer repairs - so only a present-blob-but-unlocatable path is skipped. */
        private boolean withheld(String requestPath, String named) throws IOException {
            if (requestPath.equals("/quarantine") || requestPath.startsWith("/quarantine/")) {
                return true;
            }
            return publication.located(requestPath).isEmpty() && store.exists("blobs/" + named);
        }
    }
}
