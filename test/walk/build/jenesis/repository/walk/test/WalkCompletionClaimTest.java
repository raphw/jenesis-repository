package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A lease lost at the very end of a segment - the terminal {@code DONE} commit, not a mid-walk checkpoint - must
 * stop the worker <em>quietly</em>, exactly like a lost renewal during the walk, and never fail the whole
 * {@code walk()} call. A segment shorter than one checkpoint stride never renews its lease between claim and
 * completion, so this is the ordinary fate of a small segment whose node paused past its TTL.
 */
class WalkCompletionClaimTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void a_lease_lost_exactly_at_completion_stops_quietly_instead_of_failing_the_pass() throws IOException {
        ArtifactStore store = store();
        for (int index = 0; index < 3; index++) {
            String key = "publish/" + String.format("%02d", index) + "/artifact";
            store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
        }

        // One segment, a checkpoint stride larger than the segment so no mid-walk commit renews the lease.
        StoreArtifactWalk a = new StoreArtifactWalk(1000, 1, Duration.ofMinutes(10), clock);
        StoreArtifactWalk b = new StoreArtifactWalk(1000, 1, Duration.ofMinutes(10), clock);
        List<String> aVisited = new ArrayList<>();
        List<String> bVisited = new ArrayList<>();

        // On its last item A's lease expires and B reclaims and completes the segment, so when A reaches its
        // terminal DONE commit the CAS is lost. That lost claim must be swallowed, not surfaced as an IOException.
        WalkPass[] pass = new WalkPass[1];
        assertThatCode(() -> pass[0] = a.walk(store, "test", List.of("publish"), key -> {
            aVisited.add(key);
            if (aVisited.size() == 3) {
                clock.advance(Duration.ofMinutes(11));
                b.walk(store, "test", List.of("publish"), bVisited::add);
            }
        })).doesNotThrowAnyException();

        assertThat(pass[0]).isNotNull();
        assertThat(pass[0].complete()).as("B finished the segment, so the pass is complete").isTrue();
        assertThat(bVisited).as("B reclaimed the expired segment and walked it").isNotEmpty();
    }
}
