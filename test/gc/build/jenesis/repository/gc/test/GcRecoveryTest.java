package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kill-and-resume at each phase boundary, with the shared fault-injecting store standing in for a dying node: a
 * crashed reference flush can never let a referenced blob be deleted (the walk's flush-before-checkpoint ordering
 * keeps the committed cursor honest), a crash between the blob and marker deletes converges on the next pass, and
 * a crash between mark and sweep costs a pass, never an artifact.
 */
class GcRecoveryTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore filesystem() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(2, 1, Duration.ofMinutes(10), clock));
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_crashed_reference_flush_never_lets_a_referenced_blob_be_deleted() throws IOException {
        // The nastiest window for the absolute invariant: the blob's only pointer is visited by the mark, the
        // flush of its reference batch dies, and the blob is already due (an "earlier pass" condemned marker).
        // Were the cursor committed before the flush, the resumed mark would skip the pointer, the reference
        // would be lost, and the sweep would delete a blob that serves.
        ArtifactStore inner = filesystem();
        Publication publication = new Publication(inner);
        String kept = publication.storeBlob(bytes("kept"));
        inner.writeVersioned("publish/aa/kept.jar", kept.getBytes(StandardCharsets.UTF_8), null);
        inner.writeVersioned("publish/bb/pad.jar", kept.getBytes(StandardCharsets.UTF_8), null);
        inner.writeVersioned("publish/cc/pad.jar", kept.getBytes(StandardCharsets.UTF_8), null);
        inner.writeVersioned("gc/condemned/" + kept,
                "pass=0\nsince=2026-07-01T00:00:00Z".getBytes(StandardCharsets.UTF_8), null);

        FaultInjectingStore store = FaultInjectingStore.wrap(inner)
                .failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("gc/1/refs"));
        assertThatThrownBy(() -> collector().collect(store, List.of("publish"), clock.instant()))
                .isInstanceOf(IOException.class);
        assertThat(new StoreArtifactWalk(2, 1, Duration.ofMinutes(10), clock)
                .segments(inner, "gc-mark"))
                .as("the cursor never advanced past the unflushed references")
                .allSatisfy(segment -> assertThat(segment.cursor()).isNull());

        store.heal();
        clock.advance(Duration.ofMinutes(11)); // the dead worker's claim expires
        GcPlan resumed = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(resumed.complete()).isTrue();
        assertThat(inner.exists("blobs/" + kept))
                .as("the resumed mark re-read the pointer, so the referenced blob survives").isTrue();
        assertThat(resumed.spared()).isEqualTo(1);
        assertThat(inner.exists("gc/condemned/" + kept)).as("and its stale marker converged away").isFalse();
    }

    @Test
    void a_crash_between_the_blob_and_marker_deletes_converges() throws IOException {
        ArtifactStore inner = filesystem();
        Publication publication = new Publication(inner);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));
        var _ = collector().collect(inner, List.of("publish"), clock.instant());
        assertThat(inner.exists("gc/condemned/" + orphan)).isTrue();

        FaultInjectingStore store = FaultInjectingStore.wrap(inner)
                .failNextOn(FaultInjectingStore.Op.DELETE, key -> key.equals("gc/condemned/" + orphan));
        assertThatThrownBy(() -> collector().collect(store, List.of("publish"), clock.instant()))
                .isInstanceOf(IOException.class);
        assertThat(inner.exists("blobs/" + orphan)).as("the blob went before the crash").isFalse();
        assertThat(inner.exists("gc/condemned/" + orphan)).as("its marker is the crash residue").isTrue();

        store.heal();
        clock.advance(Duration.ofMinutes(11));
        GcPlan converged = collector().collect(inner, List.of("publish"), clock.instant());
        assertThat(converged.complete()).isTrue();
        assertThat(inner.exists("gc/condemned/" + orphan))
                .as("a marker whose blob is gone is swept by the convergence leg").isFalse();
        assertThat(inner.exists("blobs/" + kept)).isTrue();
    }

    @Test
    void a_crash_between_mark_and_sweep_costs_a_pass_never_an_artifact() throws IOException {
        ArtifactStore inner = filesystem();
        Publication publication = new Publication(inner);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        // The sweep walk dies before it can claim anything: the completed mark stands, nothing was judged.
        FaultInjectingStore store = FaultInjectingStore.wrap(inner)
                .failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("walks/gc-sweep"));
        assertThatThrownBy(() -> collector().collect(store, List.of("publish"), clock.instant()))
                .isInstanceOf(IOException.class);
        assertThat(inner.exists("blobs/" + orphan)).isTrue();

        store.heal();
        clock.advance(Duration.ofMinutes(11));
        var _ = collector().collect(inner, List.of("publish"), clock.instant());
        var _ = collector().collect(inner, List.of("publish"), clock.instant());
        assertThat(inner.exists("blobs/" + orphan)).as("the orphan still converges to collected").isFalse();
        assertThat(inner.exists("blobs/" + kept)).isTrue();
        assertThat(inner.list("gc/condemned")).isEmpty();
    }
}
