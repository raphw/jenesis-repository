package build.jenesis.repository.store.testkit.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.StoreInvariants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proves the shared fixtures behave as the crash-recovery matrices depend on: each armed fault fires once and heals,
 * a crash-after-write lands the mutation while still failing the caller, a compare-and-set conflict returns false
 * rather than throwing, and the invariant checker accepts a consistent store but names a dangling pointer or an
 * unreferenced blob. The delegate is a real filesystem store, so the fixture is exercised against the same backend a
 * suite would use.
 */
class StoreTestkitTest {

    @TempDir
    Path root;

    private ArtifactStore backend;

    @BeforeEach
    void setUp() {
        backend = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void a_crash_before_write_never_lands_the_write_then_heals() throws IOException {
        FaultInjectingStore store = FaultInjectingStore.wrap(backend);
        store.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("publish/"));

        assertThatThrownBy(() -> store.writeVersioned("publish/a", bytes("x"), null))
                .isInstanceOf(IOException.class);
        assertThat(store.readVersioned("publish/a")).as("the write never landed").isEmpty();

        assertThat(store.writeVersioned("publish/a", bytes("x"), null)).as("healed after one fault").isTrue();
        assertThat(store.readVersioned("publish/a")).isPresent();
        assertThat(store.calls(FaultInjectingStore.Op.WRITE_VERSIONED)).isEqualTo(2);
    }

    @Test
    void a_crash_after_write_lands_the_write_but_still_fails_the_caller() throws IOException {
        FaultInjectingStore store = FaultInjectingStore.wrap(backend);
        store.crashAfterWrite(FaultInjectingStore.Op.WRITE, FaultInjectingStore.anyKey());

        assertThatThrownBy(() -> store.write("k", new ByteArrayInputStream(bytes("body"))))
                .isInstanceOf(IOException.class);
        assertThat(store.exists("k")).as("the mutation landed before the failure surfaced").isTrue();
    }

    @Test
    void a_compare_and_set_conflict_returns_false_without_throwing() throws IOException {
        FaultInjectingStore store = FaultInjectingStore.wrap(backend);
        store.conflictNext(FaultInjectingStore.anyKey());

        assertThat(store.writeVersioned("k", bytes("v1"), null)).as("the injected conflict").isFalse();
        assertThat(store.writeVersioned("k", bytes("v1"), null)).as("healed").isTrue();
    }

    @Test
    void the_nth_matching_call_fails_and_earlier_ones_pass() throws IOException {
        FaultInjectingStore store = FaultInjectingStore.wrap(backend);
        store.failNthOn(FaultInjectingStore.Op.DELETE, FaultInjectingStore.anyKey(), 2);

        store.write("a", new ByteArrayInputStream(bytes("1")));
        store.write("b", new ByteArrayInputStream(bytes("2")));
        assertThatCode(() -> store.delete("a")).as("first delete passes").doesNotThrowAnyException();
        assertThatThrownBy(() -> store.delete("b")).as("second delete fails").isInstanceOf(IOException.class);
    }

    @Test
    void invariants_accept_a_consistent_store() throws IOException {
        String hash = backend.writeBlob(new ByteArrayInputStream(bytes("artifact")));
        backend.writeVersioned("publish/x", bytes(hash), null);
        assertThatCode(() -> new StoreInvariants(backend).assertConsistent()).doesNotThrowAnyException();
    }

    @Test
    void invariants_catch_a_dangling_pointer() throws IOException {
        backend.writeVersioned("publish/x", bytes("deadbeef"), null);   // points at a blob that was never stored
        assertThatThrownBy(() -> new StoreInvariants(backend).assertNoDanglingPointer())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("dangling pointer");
    }

    @Test
    void invariants_catch_an_unreferenced_blob() throws IOException {
        backend.writeBlob(new ByteArrayInputStream(bytes("orphan")));   // stored, no pointer references it
        assertThatThrownBy(() -> new StoreInvariants(backend).assertNoUnreferencedBlob())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("unreferenced blob");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
