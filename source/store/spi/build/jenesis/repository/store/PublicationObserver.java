package build.jenesis.repository.store;

import module java.base;

/**
 * An after-commit observer of {@link Publication#published} and {@link Publication#unpublish} - the <em>general</em>
 * publication hook seam, whose verdict-bearing {@link PublishInterceptor} sub-interface (which {@code extends} this)
 * adds the assess/withhold/commit screen. One {@code uses PublicationObserver} clause therefore discovers both, and
 * {@link Publication} splits the discovered list by {@code instanceof PublishInterceptor} - so a base-only observer
 * plugs in here unchanged and never sits in a verdict chain. Discovered with {@link java.util.ServiceLoader} like the
 * screens, but notified only once an ingress edge has screened an accepted artifact and laid it out and fires the
 * {@link Publication#published} seam - a quarantined or rejected publish is never observed - or once a serving pointer
 * is removed, and an observer has no say in either disposition.
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

    /**
     * After-commit notice that a withhold transitioned <em>on</em> for a served identity - the transition-ON leg of the
     * withhold-change feed a durable, name-bearing derived artifact (a published index, a future catalogue) subscribes
     * to so a <em>retroactive</em> hold retracts it, not only the emit-time screen. Fired at exactly the two durable
     * withhold choke points, and only on an actual state transition (never on the sweeps' idempotent converge re-marks),
     * after the durable write, with the failure logged and contained like every other notification here:
     * <ul>
     *   <li><b>marker route</b> - a {@link Withheld withheld/&lt;hash&gt;} marker was freshly written: the descriptor
     *       carries the content hash ({@code subject.hash()}) and a {@code null} path, because one marker retracts every
     *       alias of the bytes;</li>
     *   <li><b>pointer route</b> - a fresh {@code /quarantine<servedPath>} review pointer was linked: the descriptor
     *       carries the served path (the {@code /quarantine} prefix stripped) and the pointer's hash.</li>
     * </ul>
     * Because the write commits before this fires and a failure is contained, a crash or observer failure between the
     * two can lose a single signal; a durable consumer therefore keeps its own periodic rebuild-from-truth (the full
     * walk of the two-route contract) as the crash/miss heal-all backstop - worst case identical to today's exposure,
     * normally healed by the next rebuild. The default is a no-op, so an existing observer opts into the feed without
     * every provider changing.
     */
    default void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
    }

    /** The transition-OFF mirror of {@link #onWithheld}: a {@code withheld/<hash>} marker was cleared (descriptor
     *  carries the hash, path null) or a {@code /quarantine<servedPath>} review pointer was removed (descriptor carries
     *  the stripped served path and the pointer's hash). Fired only on an actual transition, after the durable delete,
     *  failures logged and contained; the same two-route contract applies - a lost clear signal is healed by the
     *  consumer's periodic rebuild. Default no-op. */
    default void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
    }
}
