/**
 * The default {@code ArtifactWalk} reference implementation over the store's own key layout - the generalisation of
 * the private depth-first inventory walk into the shared, totally ordered, resumable, range-segmented, multi-node
 * walk the SPI promises, provided as {@code store} (the default selection) and discovered with {@code ServiceLoader}.
 * It enumerates exclusively through the {@code ArtifactStore.page} ordered-paging primitive, so a flat millions-entry
 * namespace is never materialised as one list and a resume deep inside it is a seek on a backend that pages natively.
 * All pass state lives in the walked store ({@code walks/<consumer>/...}, compare-and-set objects only) - persist
 * only through the store, so a pass survives process death on any node sharing it. Settings:
 * {@code jenesis.walk.checkpoint} (cursor-commit stride, default 1000), {@code jenesis.walk.segments} (target
 * segment count per pass, default 32), {@code jenesis.walk.ttl} (claim lease seconds, default 900).
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.walk.store {
    requires build.jenesis.repository.walk;
    exports build.jenesis.repository.walk.store to build.jenesis.repository.walk.test;
    provides build.jenesis.repository.walk.WalkProvider
            with build.jenesis.repository.walk.store.StoreWalkProvider;
}
