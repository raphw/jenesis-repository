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
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dry run: {@code plan} previews exactly what the next {@code collect} would reclaim, judged from the durable
 * bookkeeping of earlier passes, and writes nothing at all - the store is byte-identical afterwards.
 */
class GcPlanTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Every key and its content under {@code prefix}, so "writes nothing" is asserted byte-for-byte. */
    private static Map<String, String> snapshot(ArtifactStore store, String prefix) throws IOException {
        Map<String, String> keys = new TreeMap<>();
        for (String child : store.list(prefix)) {
            String full = prefix.isEmpty() ? child : prefix + "/" + child;
            if (store.exists(full)) {
                keys.put(full, store.readVersioned(full)
                        .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8)).orElse(""));
            } else {
                keys.putAll(snapshot(store, full));
            }
        }
        return keys;
    }

    @Test
    void the_dry_run_previews_the_due_blobs_and_writes_nothing() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        GcPlan before = collector().plan(store, List.of("publish"), clock.instant());
        assertThat(before.complete()).as("no pass ever ran - there is no earlier judgment to preview").isFalse();
        assertThat(before.isEmpty()).isTrue();

        var _ = collector().collect(store, List.of("publish"), clock.instant());
        Map<String, String> frozen = snapshot(store, "");
        GcPlan plan = collector().plan(store, List.of("publish"), clock.instant());
        assertThat(plan.complete()).isTrue();
        assertThat(plan.collected()).isEqualTo(1);
        assertThat(plan.sample()).containsExactly(orphan);
        assertThat(plan.condemned()).isZero();
        assertThat(plan.spared()).isZero();
        assertThat(snapshot(store, "")).as("a dry run writes nothing - not a marker, not a checkpoint")
                .isEqualTo(frozen);

        GcPlan applied = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(applied.collected()).as("the collection reclaims exactly what the plan previewed")
                .isEqualTo(plan.collected());
        assertThat(applied.sample()).isEqualTo(plan.sample());
        assertThat(collector().plan(store, List.of("publish"), clock.instant()).collected())
                .as("nothing is due once the store converged").isZero();
    }

    @Test
    void a_relinked_blob_disappears_from_the_preview() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String blob = publication.storeBlob(bytes("deduped"));
        var _ = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(collector().plan(store, List.of("publish"), clock.instant()).collected()).isEqualTo(1);

        publication.link("/maven/back.jar", blob);
        assertThat(collector().plan(store, List.of("publish"), clock.instant()).collected())
                .as("the write path un-condemned the blob, so nothing is due").isZero();
    }
}
