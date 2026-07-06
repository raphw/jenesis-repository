/**
 * Shared crash-recovery test fixtures over the artifact-store SPI: a {@code FaultInjectingStore} decorator that injects
 * a store fault at a chosen point (a write that never lands, a read that fails, a compare-and-set that loses its race)
 * and a {@code StoreInvariants} checker for the two store-primitive consistency invariants the {@code Publication} /
 * {@code ArtifactStore} layer owns (no dangling {@code publish/} pointer, no unreferenced {@code blobs/} object after a
 * GC). It depends only on the store SPI - no junit, no format, no server - so both this repository's and the
 * enterprise distribution's test modules can require the same fixture rather than each hand-rolling a bespoke throwing
 * decorator. The classes are test doubles; nothing here provides a service, so the module is inert on a runtime graph.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.store.testkit {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.store.testkit;
}
