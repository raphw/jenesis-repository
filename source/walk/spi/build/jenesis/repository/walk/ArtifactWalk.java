package build.jenesis.repository.walk;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * A totally ordered, resumable, range-segmented stream of store keys under root prefixes - the one shared
 * enumeration primitive every whole-store sweep (garbage collection, retention eviction, derived-metadata
 * rebuild) rides instead of hand-rolling its own {@code list()} loop. Key-level is deliberately the primitive:
 * half the consumers sweep key spaces that are not artifacts ({@code published/}, {@code pinned/}, marker
 * spaces); artifact-level views layer on top of it.
 *
 * <p><b>Order.</b> Sibling names are visited sorted, which yields one total order over all keys - <em>path
 * order</em>, plain lexicographic except that the {@code '/'} separator sorts below every other character, so a
 * subtree ({@code app/...}) sits wholly before a longer sibling name it prefixes ({@code app.txt}). Cursors,
 * ranges, resume and post-order roll-ups all ride that order. Only sibling pages are ever buffered (bounded by
 * {@link ArtifactStore#page} paging, not by tree size).
 *
 * <p><b>The pass model.</b> {@link #walk} joins the current pass for {@code consumer} - starting a fresh one when
 * none exists or the last completed - claims free segments one at a time, and streams each claimed range's keys to
 * the visitor, committing the segment's cursor (a high-water mark in key order) every checkpoint stride. The commit
 * doubles as the claim's lease renewal; a claim is only ever taken over a {@code pending} or <em>expired</em>
 * segment, never a live holder's (refuse, don't steal), and a taken-over segment resumes from its last committed
 * cursor. The call returns when this worker can claim nothing more: either the pass just completed
 * ({@link WalkPass.Status#COMPLETE}) or other holders still own the remaining segments
 * ({@link WalkPass.Status#ACTIVE} - re-invoke later, or let another node finish). Fan a pass across a worker pool
 * by calling {@code walk} from several threads; across VMs by calling it on several nodes.
 *
 * <p><b>Delivery contract</b> (documented honestly): every key present for the whole pass is visited
 * <em>exactly once per pass</em> in the absence of a crash; after a crash-resume, <em>at least once</em> for the
 * uncommitted stride tail - so every consumer must be idempotent per item (upsert / re-judge semantics). A key
 * published while the pass runs is visited by this pass if it sorts after its segment's cursor at that moment, and
 * is guaranteed by the next pass otherwise; a key removed during the pass may or may not be visited, so consumers
 * treat a visit as "existed at some point during the pass" and re-judge on read. Steady-state freshness comes from
 * publication events, not walk latency - the walk is the back-fill, refresh and self-heal backstop, never the hot
 * path.
 */
public interface ArtifactWalk {

    /** One enumerated key. A visitor failure stops the current worker's segment but leaves its claim to expire and
     *  resume from the last committed cursor, so a crash never loses the pass. */
    @FunctionalInterface
    interface KeyVisitor {

        /** Called once per key, in ascending lexicographic order within the current segment's range. */
        void visit(String key) throws IOException;

        /**
         * Called immediately before the walk durably commits {@code cursor} as processed - every checkpoint
         * stride and at segment completion ({@code cursor} is {@code null} for a segment that held no keys) - the
         * visitor's moment to flush its own buffered derived writes. The cursor lands only after this returns, so
         * a consumer that flushes here is never resumed past an item whose derived write died in a buffer: a
         * flush failure leaves the previous cursor standing, and the re-visit replays exactly what was lost. A
         * visitor that writes through per item needs nothing; the default does nothing.
         */
        default void beforeCheckpoint(String cursor) throws IOException {
        }
    }

    /**
     * Join {@code consumer}'s current pass over {@code roots} (starting a new one when none exists or the previous
     * completed), claim segments until none is claimable, and stream every claimed key to {@code visitor}. Pass and
     * segment state live under {@code walks/<consumer>/} in {@code store} - the only persistence, so a walk resumes
     * across process death on any node sharing the store. Returns the pass as this worker last saw it.
     */
    WalkPass walk(ArtifactStore store, String consumer, List<String> roots, KeyVisitor visitor) throws IOException;

    /** The current pass for {@code consumer}, empty when none was ever started - the observability read a console
     *  or fingerprint surfaces without joining the walk. */
    Optional<WalkPass> pass(ArtifactStore store, String consumer) throws IOException;

    /** The current pass's segments with their live claim state ({@code holder}, {@code expiry}, {@code cursor}) -
     *  "node X's segment cursor stuck 40 minutes" is read straight off this. Empty when no pass exists. */
    List<WalkSegment> segments(ArtifactStore store, String consumer) throws IOException;
}
