package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Asserts the store-primitive consistency invariants a crash-recovery test checks once a repair pass has run - the two
 * the free {@code Publication} / {@link ArtifactStore} layer owns, independent of any format or enterprise derived
 * surface: every {@code publish/} pointer resolves to a blob that is actually stored (no dangling pointer, so nothing
 * that "serves" 404s on the body), and, after a garbage collection, no {@code blobs/} object is left unreferenced by
 * any pointer (no leaked storage). The enterprise-derived surfaces - the {@code published/} sidecars, the rolled-up
 * {@code sizes/}, the quota counter, the forwarding outbox, the quarantine review queue - are checked by the suite
 * beside the classes that own them; this checker is the shared, dependency-light core both repositories reuse.
 *
 * <p>Each assertion throws an {@link AssertionError} naming the first violation (so it reads in a test failure without a
 * junit dependency here) and returns cleanly when the store is consistent. It walks only the tiny pointers and blob
 * sizes, never an artifact body, so it runs identically on a filesystem and an object store.
 */
public final class StoreInvariants {

    private final ArtifactStore store;

    public StoreInvariants(ArtifactStore store) {
        this.store = store;
    }

    /** Both store-primitive invariants that must hold once a repository has converged after a GC: no dangling pointer
     *  and no unreferenced blob. Use {@link #assertNoDanglingPointer} alone before a GC, when superseded blobs may
     *  legitimately linger until the collection runs. */
    public void assertConsistent() throws IOException {
        assertNoDanglingPointer();
        assertNoUnreferencedBlob();
    }

    /** Every {@code publish/} pointer (a served path or a {@code /quarantine} hold) references a blob that is stored -
     *  a pointer whose blob is missing would "serve" a path whose body is gone. Holds at all times, before or after a
     *  GC. */
    public void assertNoDanglingPointer() throws IOException {
        for (String pointer : pointers()) {
            String hash = read(pointer);
            if (hash != null && !store.exists("blobs/" + hash)) {
                throw new AssertionError("dangling pointer " + pointer + " -> blobs/" + hash + " (blob not stored)");
            }
        }
    }

    /** No stored {@code blobs/} object is unreferenced by any {@code publish/} pointer - the invariant a garbage
     *  collection restores. Assert only after the GC pass has run; a superseded republish or a rejected upload leaves
     *  an unreferenced blob until then, by design. */
    public void assertNoUnreferencedBlob() throws IOException {
        Set<String> referenced = new HashSet<>();
        for (String pointer : pointers()) {
            String hash = read(pointer);
            if (hash != null) {
                referenced.add(hash);
            }
        }
        for (String blob : store.list("blobs")) {
            if (!referenced.contains(blob)) {
                throw new AssertionError("unreferenced blob blobs/" + blob + " (no publish/ pointer references it)");
            }
        }
    }

    /** Every leaf pointer under the {@code publish/} tree. */
    private List<String> pointers() {
        List<String> leaves = new ArrayList<>();
        collect("publish", "publish", leaves);
        return leaves;
    }

    /** The blob hash a pointer holds, trimmed, or null if the object is absent. */
    private String read(String pointer) throws IOException {
        return store.readVersioned(pointer)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim())
                .orElse(null);
    }

    private void collect(String root, String prefix, List<String> leaves) {
        List<String> children = store.list(prefix);
        if (children.isEmpty()) {
            if (!prefix.equals(root)) {
                leaves.add(prefix);
            }
            return;
        }
        for (String child : children) {
            collect(root, prefix + "/" + child, leaves);
        }
    }
}
