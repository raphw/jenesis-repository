package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The multi-node half of the walk contract, with two walk instances over one store standing in for two VMs:
 * concurrent workers take disjoint segments (no double work, no gap), a live holder's claim is refused - never
 * stolen - and an expired holder's segment is reclaimed from its last committed cursor.
 */
class WalkClaimTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static List<String> seed(ArtifactStore store, int count) throws IOException {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String key = "publish/" + String.format("%c%02d", 'a' + index % 8, index) + "/artifact";
            keys.add(key);
            store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
        }
        return keys.stream().sorted().toList();
    }

    @Test
    void two_nodes_split_one_pass_with_no_double_work_and_no_gap() throws Exception {
        ArtifactStore store = store();
        List<String> keys = seed(store, 40);
        ConcurrentLinkedQueue<String> visited = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<WalkPass>> passes = new ArrayList<>();
            for (int node = 0; node < 2; node++) {
                StoreArtifactWalk walk = new StoreArtifactWalk(3, 8, Duration.ofMinutes(10), clock);
                passes.add(pool.submit(() -> {
                    start.await();
                    return walk.walk(store, "test", List.of("publish"), visited::add);
                }));
            }
            start.countDown();
            for (Future<WalkPass> pass : passes) {
                assertThat(pass.get().generation()).isEqualTo(1);
            }
        }
        assertThat(visited).doesNotHaveDuplicates();
        assertThat(visited).containsExactlyInAnyOrderElementsOf(keys);
        StoreArtifactWalk reader = new StoreArtifactWalk(3, 8, Duration.ofMinutes(10), clock);
        assertThat(reader.pass(store, "test")).hasValueSatisfying(pass -> assertThat(pass.complete()).isTrue());
        assertThat(reader.segments(store, "test"))
                .allSatisfy(segment -> assertThat(segment.state()).isEqualTo(WalkSegment.State.DONE));
    }

    @Test
    void a_live_claim_is_refused_and_an_expired_one_is_reclaimed_from_its_cursor() throws IOException {
        ArtifactStore store = store();
        List<String> keys = seed(store, 10);
        List<String> before = new ArrayList<>();
        assertThatThrownBy(() -> new StoreArtifactWalk(2, 1, Duration.ofMinutes(10), clock)
                .walk(store, "test", List.of("publish"), key -> {
                    before.add(key);
                    if (before.size() == 5) {
                        throw new IOException("node death");
                    }
                })).hasMessageContaining("node death");

        // The dead node's claim is still live: another node is refused, not handed the segment.
        StoreArtifactWalk other = new StoreArtifactWalk(2, 1, Duration.ofMinutes(10), clock);
        List<String> refused = new ArrayList<>();
        WalkPass held = other.walk(store, "test", List.of("publish"), refused::add);
        assertThat(held.complete()).isFalse();
        assertThat(refused).as("a live holder is never robbed").isEmpty();

        // Once the lease runs out the same node reclaims the segment from its last committed cursor.
        clock.advance(Duration.ofMinutes(11));
        List<String> after = new ArrayList<>();
        WalkPass reclaimed = other.walk(store, "test", List.of("publish"), after::add);
        assertThat(reclaimed.complete()).isTrue();
        assertThat(reclaimed.generation()).isEqualTo(1);
        Set<String> union = new HashSet<>(before);
        union.addAll(after);
        assertThat(union).containsExactlyInAnyOrderElementsOf(keys);
        Set<String> revisited = new HashSet<>(before);
        revisited.retainAll(after);
        assertThat(revisited.size()).as("the take-over resumes from the cursor, not from scratch")
                .isLessThanOrEqualTo(2);
    }
}
