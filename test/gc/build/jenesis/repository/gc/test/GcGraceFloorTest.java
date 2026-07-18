package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
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

/**
 * The optional wall-clock grace floor ({@code jenesis.gc.grace}): on top of the one-pass generation gap, a condemned
 * blob is not collected until it has also carried its marker for at least the configured duration - so a burst of
 * collections (several nodes, or a lease-expiry re-collect) that advances generations faster than the nominal
 * interval cannot shorten the grace an in-flight publish gets. With the default (zero) floor the grace stays purely
 * generation-based, which the rest of the suite covers.
 */
class GcGraceFloorTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector(Duration graceFloor) {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock), graceFloor);
    }

    @Test
    void a_floor_holds_a_condemned_blob_across_generations_until_the_wall_clock_elapses() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String orphan = publication.storeBlob(new ByteArrayInputStream("orphan".getBytes(StandardCharsets.UTF_8)));

        // Pass 1 condemns the orphan, stamping the marker with this instant.
        GcPlan first = collector(Duration.ofHours(1)).collect(store, List.of("publish"), clock.instant());
        assertThat(first.condemned()).isEqualTo(1);
        assertThat(store.exists("gc/condemned/" + orphan)).isTrue();

        // Pass 2 is a later generation - the generation gap alone would delete now - but only minutes have passed,
        // short of the one-hour floor, so the blob is held.
        clock.advance(Duration.ofMinutes(10));
        GcPlan second = collector(Duration.ofHours(1)).collect(store, List.of("publish"), clock.instant());
        assertThat(second.collected()).as("under the wall-clock floor, the blob is not yet due").isZero();
        assertThat(store.exists("blobs/" + orphan)).isTrue();

        // Once the wall clock passes the floor, a further pass collects it.
        clock.advance(Duration.ofHours(1));
        GcPlan third = collector(Duration.ofHours(1)).collect(store, List.of("publish"), clock.instant());
        assertThat(third.collected()).isEqualTo(1);
        assertThat(store.exists("blobs/" + orphan)).isFalse();
    }

    @Test
    void the_dry_run_applies_the_same_grace_floor_as_collect_so_they_agree() throws IOException {
        // plan() must apply the wall-clock grace floor collect() enforces, or the dry run over-reports every
        // condemned blob still inside its grace window - previewing a reclamation the next collect would withhold.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String orphan = publication.storeBlob(new ByteArrayInputStream("orphan".getBytes(StandardCharsets.UTF_8)));

        // Pass 1 condemns the orphan under a one-hour floor, stamping the marker with this instant.
        assertThat(collector(Duration.ofHours(1)).collect(store, List.of("publish"), clock.instant()).condemned())
                .isEqualTo(1);

        // Under the floor: the dry run previews nothing due, and a real collect withholds it too - they agree.
        clock.advance(Duration.ofMinutes(10));
        assertThat(collector(Duration.ofHours(1)).plan(store, List.of("publish"), clock.instant()).collected())
                .as("under the wall-clock floor the dry run previews nothing due").isZero();
        assertThat(collector(Duration.ofHours(1)).collect(store, List.of("publish"), clock.instant()).collected())
                .as("and the collect withholds it too").isZero();

        // Past the floor: the dry run previews the blob due, matching the collect that then reclaims it.
        clock.advance(Duration.ofHours(1));
        assertThat(collector(Duration.ofHours(1)).plan(store, List.of("publish"), clock.instant()).collected())
                .as("past the floor the dry run previews the blob due").isEqualTo(1);
        assertThat(collector(Duration.ofHours(1)).collect(store, List.of("publish"), clock.instant()).collected())
                .as("and the collect reclaims exactly what the dry run previewed").isEqualTo(1);
        assertThat(store.exists("blobs/" + orphan)).isFalse();
    }

    @Test
    void a_zero_floor_is_the_generation_only_default() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String orphan = publication.storeBlob(new ByteArrayInputStream("orphan".getBytes(StandardCharsets.UTF_8)));

        // No clock advance at all: the generation gap alone collects on the second pass, exactly as today.
        assertThat(collector(Duration.ZERO).collect(store, List.of("publish"), clock.instant()).condemned())
                .isEqualTo(1);
        assertThat(collector(Duration.ZERO).collect(store, List.of("publish"), clock.instant()).collected())
                .isEqualTo(1);
        assertThat(store.exists("blobs/" + orphan)).isFalse();
    }
}
