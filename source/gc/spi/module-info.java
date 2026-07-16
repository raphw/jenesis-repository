/**
 * The garbage-collection SPI: reclaiming the content blobs no live pointer references any more is a discovered,
 * exclusive capability ({@code jenesis.repository.gc=<name>}), kept separate from its implementation so the
 * reclamation strategy can change without breaking a caller - and <em>no-op by absence</em>: with no module
 * installed or selected, {@code GarbageCollectorProvider.resolve} is empty, nothing is ever reclaimed, and the
 * capability surfaces say garbage collection is off. No blob is deleted by a deployment that did not opt into a
 * collector. A {@code GarbageCollector} matches the retention sweeper's shape - {@code plan} is the dry run a
 * maintenance console previews, {@code collect} computes and applies - and is handed the pointer roots by its
 * caller (always {@code publish}; a blobs-namespace format's declared roots are added by the caller that knows
 * them), because which namespaces hold serving pointers is layout knowledge this free primitive deliberately does
 * not have. Deletion is the one unrecoverable act in the product, so an implementation is held to the invariant
 * that a referenced, re-linked or in-flight blob is never deleted; the write path cooperates by clearing the
 * collector's {@code gc/condemned/<hash>} marker whenever a pointer links a blob ({@code Publication.link}).
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.gc {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.gc;
    uses build.jenesis.repository.gc.GarbageCollectorProvider;
}
