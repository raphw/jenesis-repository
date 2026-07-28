package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.StoreInvariants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-ordering invariant the {@code Publication} model owns, and the minimal-harm reconcile it makes possible.
 * Every multi-object write lands the blob ({@code blobs/<hash>}) durably before the pointer ({@code publish/<path> ->
 * <hash>}) that names it - {@link Publication#storeBlob} then {@link Publication#link} - so a crash between the two
 * can only ever leave a benign orphan blob (the garbage collector's domain), never a pointer resolving to missing
 * bytes. This pins that guarantee by injecting the crash at the exact torn moment and proving the residue is an orphan,
 * not a dangling pointer; and it shows that the free store primitives ({@link ArtifactStore#list} /
 * {@link ArtifactStore#readVersioned} / {@link ArtifactStore#exists} / {@link ArtifactStore#delete}, checked by
 * {@link StoreInvariants}) are enough to detect and repair a dangling pointer should one ever arise out of band,
 * converging the store to fully-absent and re-running as a no-op - the free-core reconcile the enterprise
 * MaintenanceTask schedules and audits.
 */
class WriteOrderingReconcileTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_crash_between_the_blob_and_pointer_writes_leaves_an_orphan_never_a_dangling_pointer() throws IOException {
        // The blob write lands; the pointer write crashes. Because the blob is durable first, the residue is a blob
        // with no pointer - a benign orphan - and never a pointer pointing at bytes that are not there.
        FaultInjectingStore faulting = FaultInjectingStore.wrap(store);
        faulting.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish"));
        Publication publication = new Publication(faulting);

        String hash = publication.storeBlob(bytes("payload"));
        assertThatThrownBy(() -> publication.link("/raw/a/b", hash))
                .as("the pointer write crashes after the blob is durable").isInstanceOf(IOException.class);

        assertThat(store.exists("blobs/" + hash)).as("the blob is durable - an orphan").isTrue();
        assertThat(store.readVersioned("publish/raw/a/b")).as("no pointer was written").isEmpty();
        // The invariant that must never break: no dangling pointer, whatever the crash.
        new StoreInvariants(store).assertNoDanglingPointer();
    }

    @Test
    void a_dangling_pointer_is_detectable_and_repairable_with_bare_store_primitives() throws IOException {
        // Force the harmful state the ordering forbids (an out-of-band corruption): a pointer naming a blob that is not
        // stored. The store-invariant checker over the free primitives detects it.
        String missing = "b".repeat(64);
        store.writeVersioned("publish/raw/x", missing.getBytes(StandardCharsets.UTF_8), null);
        assertThatThrownBy(() -> new StoreInvariants(store).assertNoDanglingPointer())
                .as("the dangling pointer is detected").isInstanceOf(AssertionError.class);

        // The reconcile: remove the dangling pointer with the bare delete primitive - it serves nothing anyway, since
        // located() already filters a pointer whose blob is gone to empty.
        assertThat(new Publication(store).located("/raw/x")).as("a dangling pointer already serves nothing").isEmpty();
        store.delete("publish/raw/x");

        // Converged to fully-absent, and a re-check (the idempotent re-run) is a clean no-op.
        new StoreInvariants(store).assertNoDanglingPointer();
        assertThat(store.readVersioned("publish/raw/x")).isEmpty();
    }

    @Test
    void a_referenced_object_stays_consistent_and_is_never_disturbed() throws IOException {
        // A normal, fully published artifact: blob durable first, pointer second. Both store-primitive invariants hold
        // (no dangling pointer, no unreferenced blob), so a reconcile has nothing to do and removes nothing.
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(bytes("kept"));
        publication.link("/raw/keep/me", hash);

        new StoreInvariants(store).assertConsistent();
        assertThat(publication.located("/raw/keep/me")).contains("blobs/" + hash);
        assertThat(store.exists("blobs/" + hash)).isTrue();
    }
}
