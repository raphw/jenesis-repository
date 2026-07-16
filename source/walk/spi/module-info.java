/**
 * The shared artifact-walk SPI: one totally ordered, resumable, range-segmented, multi-node-aware enumeration of a
 * store's key space, kept separate from its implementation so the enumeration strategy can change without breaking a
 * consumer. Everywhere the whole artifact set must be enumerated - garbage collection, retention eviction, every
 * derived-metadata rebuild - goes through {@code ArtifactWalk}, never a private {@code list()} loop. A walk pass is
 * durable in the walked store itself ({@code walks/<consumer>/...}): each segment is one compare-and-set state object
 * embedding its claim ({@code range, state, holder, expiry, cursor}), so threads <em>and different VMs</em> take
 * disjoint segments, a checkpoint doubles as lease renewal, and a dead node's segment is reclaimed from its last
 * committed cursor - the walk never restarts from scratch. Delivery is exactly-once per pass in the absence of a
 * crash and at-least-once for the uncommitted stride tail after a crash-resume, so every consumer must be idempotent
 * per item. An implementation ships as its own module that {@code provides} a {@code WalkProvider}, discovered with
 * {@code ServiceLoader} and selected with {@code jenesis.repository.walk=<name>}; with none installed
 * {@code WalkProvider.resolve} is empty and every walk-riding surface degrades gracefully. {@code WalkConsumer} is
 * the walk half of the two-route derived-metadata contract (steady state = publication events; back-fill, periodic
 * refresh and self-heal = the walk), discovered the same way and driven by the shared {@code RebuildPass} - one
 * enumeration of the pointer roots feeding every discovered consumer.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.walk {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.walk;
    uses build.jenesis.repository.walk.WalkProvider;
    uses build.jenesis.repository.walk.WalkConsumer;
}
