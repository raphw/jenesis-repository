package build.jenesis.repository.store.testkit.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.StoreInvariants;
import module org.junit.jupiter.api;

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

    @Test
    void write_batch_reports_committed_outcomes_in_input_order() throws IOException {
        List<ArtifactStore.BatchOutcome> outcomes = backend.writeBatch(List.of(
                new ArtifactStore.BatchWrite("meta/a", bytes("A"), null),
                new ArtifactStore.BatchWrite("meta/b", bytes("B"), null),
                new ArtifactStore.BatchWrite("meta/c", bytes("C"), null)));

        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::key).containsExactly("meta/a", "meta/b", "meta/c");
        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::status)
                .containsOnly(ArtifactStore.BatchOutcome.Status.COMMITTED);
        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::failure).containsOnlyNulls();
        assertThat(read("meta/a")).isEqualTo("A");
        assertThat(read("meta/b")).isEqualTo("B");
        assertThat(read("meta/c")).isEqualTo("C");
    }

    @Test
    void write_batch_reports_a_mid_batch_conflict_while_the_others_still_apply() throws IOException {
        FaultInjectingStore store = FaultInjectingStore.wrap(backend);
        store.conflictNext(FaultInjectingStore.keyContaining("meta/b"));   // the middle key loses its compare-and-set

        List<ArtifactStore.BatchOutcome> outcomes = store.writeBatch(List.of(
                new ArtifactStore.BatchWrite("meta/a", bytes("A"), null),
                new ArtifactStore.BatchWrite("meta/b", bytes("B"), null),
                new ArtifactStore.BatchWrite("meta/c", bytes("C"), null)));

        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::status).containsExactly(
                ArtifactStore.BatchOutcome.Status.COMMITTED,
                ArtifactStore.BatchOutcome.Status.CONFLICTED,
                ArtifactStore.BatchOutcome.Status.COMMITTED);
        assertThat(read("meta/a")).as("the key before the conflict landed").isEqualTo("A");
        assertThat(store.readVersioned("meta/b")).as("the conflicted key did not land").isEmpty();
        assertThat(read("meta/c")).as("a conflict on one key never aborts the rest of the batch").isEqualTo("C");
    }

    @Test
    void write_batch_reports_a_failure_carrying_its_exception_and_continues() throws IOException {
        FaultInjectingStore store = FaultInjectingStore.wrap(backend);
        store.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyContaining("meta/b"));

        List<ArtifactStore.BatchOutcome> outcomes = store.writeBatch(List.of(
                new ArtifactStore.BatchWrite("meta/a", bytes("A"), null),
                new ArtifactStore.BatchWrite("meta/b", bytes("B"), null),
                new ArtifactStore.BatchWrite("meta/c", bytes("C"), null)));

        assertThat(outcomes.get(0).status()).isEqualTo(ArtifactStore.BatchOutcome.Status.COMMITTED);
        assertThat(outcomes.get(1).status()).isEqualTo(ArtifactStore.BatchOutcome.Status.FAILED);
        assertThat(outcomes.get(1).failure()).as("the FAILED outcome carries the IOException, not swallowed")
                .isInstanceOf(IOException.class);
        assertThat(outcomes.get(2).status()).as("a failure on one key never aborts the rest")
                .isEqualTo(ArtifactStore.BatchOutcome.Status.COMMITTED);
        assertThat(read("meta/c")).isEqualTo("C");
    }

    @Test
    void write_batch_preserves_each_keys_compare_and_set_token() throws IOException {
        backend.writeVersioned("meta/x", bytes("v1"), null);
        backend.writeVersioned("meta/y", bytes("v1"), null);
        Object tokenX = backend.readVersioned("meta/x").orElseThrow().token();

        // Each entry keeps writeVersioned's token semantics independently: x updates on its current token (commit),
        // y is offered a stale token (conflict) - and the conflict on y never disturbs the committed x.
        List<ArtifactStore.BatchOutcome> outcomes = backend.writeBatch(List.of(
                new ArtifactStore.BatchWrite("meta/x", bytes("x2"), tokenX),
                new ArtifactStore.BatchWrite("meta/y", bytes("y2"), "stale-token")));

        assertThat(outcomes.get(0).status()).isEqualTo(ArtifactStore.BatchOutcome.Status.COMMITTED);
        assertThat(outcomes.get(1).status()).isEqualTo(ArtifactStore.BatchOutcome.Status.CONFLICTED);
        assertThat(read("meta/x")).as("the matched token updated the value").isEqualTo("x2");
        assertThat(read("meta/y")).as("the stale token left the value untouched").isEqualTo("v1");
    }

    @Test
    void write_batch_parallel_classifies_a_mid_batch_conflict_the_same_way_as_the_default_loop() throws IOException {
        FaultInjectingStore store = FaultInjectingStore.wrap(backend);
        store.conflictNext(FaultInjectingStore.keyContaining("meta/b"));

        // The shared helper is the object-store overrides' body; drive it directly against a real store so the
        // parallel path's ordered outcomes and conflict classification are proven without a Docker-gated backend.
        List<ArtifactStore.BatchOutcome> outcomes = ArtifactStore.writeBatchParallel(store, List.of(
                new ArtifactStore.BatchWrite("meta/a", bytes("A"), null),
                new ArtifactStore.BatchWrite("meta/b", bytes("B"), null),
                new ArtifactStore.BatchWrite("meta/c", bytes("C"), null)));

        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::key)
                .as("outcomes stay in input order even though disjoint keys fan out")
                .containsExactly("meta/a", "meta/b", "meta/c");
        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::status).containsExactly(
                ArtifactStore.BatchOutcome.Status.COMMITTED,
                ArtifactStore.BatchOutcome.Status.CONFLICTED,
                ArtifactStore.BatchOutcome.Status.COMMITTED);
        assertThat(read("meta/a")).isEqualTo("A");
        assertThat(read("meta/c")).isEqualTo("C");
    }

    @Test
    void write_batch_parallel_keeps_input_order_across_many_keys() throws IOException {
        List<ArtifactStore.BatchWrite> writes = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            writes.add(new ArtifactStore.BatchWrite("meta/" + String.format("%02d", index), bytes("v" + index), null));
        }
        List<ArtifactStore.BatchOutcome> outcomes = ArtifactStore.writeBatchParallel(backend, writes);

        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::key)
                .containsExactlyElementsOf(writes.stream().map(ArtifactStore.BatchWrite::key).toList());
        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::status)
                .containsOnly(ArtifactStore.BatchOutcome.Status.COMMITTED);
    }

    @Test
    void write_batch_parallel_never_overlaps_two_writes_to_the_same_key() throws IOException {
        // Two creates of one key: run in input order the first commits and the second conflicts (the key now exists).
        // If the same-key writes overlapped or reordered, the outcome pair would be non-deterministic; grouping
        // same-key indices onto one task makes it exactly COMMITTED-then-CONFLICTED every run.
        List<ArtifactStore.BatchOutcome> outcomes = ArtifactStore.writeBatchParallel(backend, List.of(
                new ArtifactStore.BatchWrite("meta/same", bytes("first"), null),
                new ArtifactStore.BatchWrite("meta/same", bytes("second"), null)));

        assertThat(outcomes).extracting(ArtifactStore.BatchOutcome::status).containsExactly(
                ArtifactStore.BatchOutcome.Status.COMMITTED, ArtifactStore.BatchOutcome.Status.CONFLICTED);
        assertThat(read("meta/same")).as("the first write's value survives; the reordered second never overwrote it")
                .isEqualTo("first");
    }

    private String read(String key) throws IOException {
        return new String(backend.readVersioned(key).orElseThrow().content(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
