package build.jenesis.repository.store;

import module java.base;

/**
 * An after-commit observer of {@link Publication#publish} and {@link Publication#unpublish} - the second hook class
 * beside the verdict-bearing {@link PublishInterceptor}. Discovered with {@link java.util.ServiceLoader} like the
 * screens, but notified only once an accepted artifact's pointer is linked and serving - a quarantined or rejected
 * publish is never observed - or once a serving pointer is removed, and an observer has no say in either disposition.
 * This is the seam for what rides a publication change without sitting in its verdict path - forwarding to another
 * repository, a webhook, replication, handing a deeper scan to a worker: an observer's failure is logged and contained
 * (it never unlinks the artifact, fails the upload or blocks the removal), and anything slow belongs in a background
 * worker the observer only leaves a note for - record a small store object here, drain it elsewhere - so a remote
 * target's latency or outage never couples into the local publish.
 *
 * <p><b>The two-route derived-metadata contract.</b> A plugin that derives metadata from what is published (an index,
 * a counter, a dependents table) keeps it correct by exactly two routes, and a correct plugin uses <em>both</em>:
 * these <b>live events</b> ({@link #onPublished} / {@link #onDeleted}) for the steady state, and <b>the full walk</b>
 * - the walk SPI's {@code WalkConsumer} ({@code build.jenesis.repository.walk}), whose {@code onRetained} streams
 * every retained artifact from one shared, resumable enumeration - for first-activation back-fill, periodic refresh
 * and self-heal. Events alone miss what happened while the plugin was absent or crashed; the walk alone is periodic,
 * not live. The walk must be able to fully rebuild the plugin's derived state wherever the data is re-derivable from
 * the durable store; primary rows that record a human decision or a point-in-time observation (a pin, an override, a
 * download marker) are never "rebuilt" and are excluded by design.
 */
public interface PublicationObserver {

    /** React to a committed publish: the linked {@link ArtifactDescriptor} (content-addressed hash and size set) and
     *  the same scoped store it was published through, so a recorded follow-up (an outbox entry, a replication
     *  marker) lands under exactly the space the artifact did. */
    void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException;

    /** React to a removed serving pointer, fired once per pointer with the descriptor richness the removal site has:
     *  {@link Publication#unpublish} knows the request path and the blob hash the pointer named (the free store knows
     *  no layouts - a coordinate-needing observer describes the path through its format), while a layout-aware
     *  eviction enriches the descriptor with ecosystem and coordinate. A garbage collector's blob reclamation fires
     *  nothing - an unreferenced blob serves nothing, so no pointer-derived metadata can reference it, by
     *  construction. The default is a no-op, so an observer opts into removals without every existing one changing. */
    default void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
    }
}
