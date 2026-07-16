package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.StoreInvariants;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The multi-node half, with two collector instances over one store standing in for two VMs: a dead collector's
 * pass is refused while its claim lives (no double work, nothing judged early) and taken over from its cursor once
 * the lease expires; and two collectors running at once never violate the data-safety invariants.
 */
class GcClaimTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore filesystem() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector(int segments) {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(2, segments, Duration.ofMinutes(10), clock));
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_dead_collectors_pass_is_refused_until_its_lease_expires() throws IOException {
        ArtifactStore inner = filesystem();
        Publication publication = new Publication(inner);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        // Node A dies on its first reference flush, its mark claim still live.
        FaultInjectingStore dying = FaultInjectingStore.wrap(inner)
                .failEveryOn(FaultInjectingStore.Op.WRITE_VERSIONED, FaultInjectingStore.keyPrefix("gc/1/refs"));
        assertThatThrownBy(() -> collector(1).collect(dying, List.of("publish"), clock.instant()))
                .isInstanceOf(IOException.class);

        // Node B is refused the live claim: its pass is incomplete and it judged nothing - no double work.
        GcPlan refused = collector(1).collect(inner, List.of("publish"), clock.instant());
        assertThat(refused.complete()).as("a live holder's segment is never taken").isFalse();
        assertThat(refused.isEmpty()).isTrue();
        assertThat(inner.list("gc/condemned")).as("nothing was judged off an incomplete mark").isEmpty();

        // Once the lease runs out, node B finishes the same pass and the judgment is whole.
        clock.advance(Duration.ofMinutes(11));
        GcPlan reclaimed = collector(1).collect(inner, List.of("publish"), clock.instant());
        assertThat(reclaimed.complete()).isTrue();
        assertThat(reclaimed.condemned()).isEqualTo(1);
        assertThat(inner.exists("blobs/" + kept)).isTrue();
        assertThat(inner.exists("blobs/" + orphan)).as("condemned, not yet collected").isTrue();
    }

    @Test
    void two_concurrent_collectors_share_one_store_safely() throws Exception {
        ArtifactStore store = filesystem();
        Publication publication = new Publication(store);
        List<String> kept = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String linked = publication.storeBlob(bytes("kept " + index));
            publication.link("/maven/kept-" + index + ".jar", linked);
            kept.add(linked);
            orphans.add(publication.storeBlob(bytes("orphan " + index)));
        }

        for (int round = 0; round < 5 && orphans.stream().anyMatch(hash -> store.exists("blobs/" + hash)); round++) {
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                List<Future<GcPlan>> results = new ArrayList<>();
                for (int node = 0; node < 2; node++) {
                    MarkSweepGarbageCollector collector = collector(4);
                    results.add(pool.submit(() -> {
                        start.await();
                        return collector.collect(store, List.of("publish"), clock.instant());
                    }));
                }
                start.countDown();
                for (Future<GcPlan> result : results) {
                    var _ = result.get(); // a node refused mid-pass reports incomplete - that is fine
                }
            }
        }

        for (String hash : kept) {
            assertThat(store.exists("blobs/" + hash)).as("a referenced blob survives concurrent collectors").isTrue();
        }
        for (String hash : orphans) {
            assertThat(store.exists("blobs/" + hash)).as("every orphan converged to collected").isFalse();
        }
        assertThat(store.list("gc/condemned")).isEmpty();
        new StoreInvariants(store).assertConsistent();
    }
}
