package build.jenesis.repository.walk;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;

import module java.base;

/**
 * The walk half of the two-route derived-metadata contract. A plugin keeps its derived state correct by exactly two
 * routes, and a correct plugin implements <em>both</em>: <b>live events</b> ({@code PublicationObserver}'s
 * {@code onPublished} / {@code onDeleted}) for the steady state, and <b>the full walk</b> - this interface - for
 * first-activation back-fill, periodic refresh and self-heal. A scheduled walk pass drives every discovered consumer
 * from <em>one</em> enumeration, so N metadata rebuilders never mean N tree walks. The walk alone must be able to
 * fully rebuild the plugin's derived state from the durable store wherever the truth model permits; where a surface
 * genuinely cannot be re-derived (a human decision, a point-in-time observation), the plugin's documentation names
 * it and the plugin degrades gracefully rather than serving a silently-incomplete view as if it were whole.
 *
 * <p>{@link #onRetained} is called once per retained artifact per pass - and, across a crash-resume, at least once
 * for the uncommitted stride tail - so it must be <em>idempotent</em> (upsert / re-judge semantics). A streaming
 * consumer (a reconcile leg, a sidecar heal, a per-shard index) simply resumes mid-pass with the segment cursor; a
 * snapshot rebuilder (one artifact committed at pass end) restarts its own accumulation after a crash and says so -
 * degrade-and-say-so is recorded per consumer, never silent.
 */
public interface WalkConsumer {

    /** The consumer's name - its signal and settings namespace, and its {@code walks/<name>/} pass-state scope. */
    String name();

    /** One retained artifact, visited in total key order; must be idempotent per artifact (see the class contract
     *  for the exactly-once-per-pass / at-least-once-across-a-crash delivery semantics). */
    void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException;

    /** The pass is starting - the moment a snapshot rebuilder resets its accumulation. */
    default void onPassStarted(WalkPass pass) {
    }

    /** The pass enumerated everything - the commit / compact / heal hook for a consumer that acts at pass end. */
    default void onPassCompleted(WalkPass pass) {
    }

    /** Every enabled consumer discovered via {@link ServiceLoader} (a parallel SPI: a
     *  {@code jenesis.repository.<name>=false} skips one, {@link Features}), in discovery order - what the scheduled
     *  walk pass drives from its one enumeration. */
    static List<WalkConsumer> discovered() {
        List<WalkConsumer> consumers = new ArrayList<>();
        for (WalkConsumer consumer : ServiceLoader.load(WalkConsumer.class)) {
            if (Features.enabled(consumer.name())) {
                consumers.add(consumer);
            }
        }
        return List.copyOf(consumers);
    }
}
