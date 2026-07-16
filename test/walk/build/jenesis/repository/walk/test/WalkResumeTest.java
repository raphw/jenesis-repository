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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The resumability half of the walk contract: a crash at <em>every</em> stride position resumes from the last
 * committed cursor on the same pass - no item is ever missed, at most one checkpoint stride is re-visited, and the
 * pass never restarts from scratch.
 */
class WalkResumeTest {

    private static final int CHECKPOINT = 5;

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store(String name) {
        Path scoped = root.resolve(name);
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? scoped.toString() : null);
    }

    @Test
    void a_crash_at_every_stride_position_resumes_with_at_most_one_stride_revisited() throws IOException {
        List<String> keys = new ArrayList<>();
        for (char letter = 'a'; letter <= 'z'; letter++) {
            keys.add("publish/" + letter + "/artifact");
        }
        for (int kill = 1; kill <= keys.size(); kill++) {
            ArtifactStore store = store("kill-" + kill);
            for (String key : keys) {
                store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
            }
            List<String> before = new ArrayList<>();
            int fatal = kill;
            assertThatThrownBy(() -> new StoreArtifactWalk(CHECKPOINT, 1, Duration.ofMinutes(10), clock)
                    .walk(store, "test", List.of("publish"), key -> {
                        before.add(key);
                        if (before.size() == fatal) {
                            throw new IOException("crash after " + fatal);
                        }
                    })).hasMessageContaining("crash after " + fatal);
            clock.advance(Duration.ofMinutes(11));
            List<String> after = new ArrayList<>();
            WalkPass resumed = new StoreArtifactWalk(CHECKPOINT, 1, Duration.ofMinutes(10), clock)
                    .walk(store, "test", List.of("publish"), after::add);
            assertThat(resumed.complete()).isTrue();
            assertThat(resumed.generation()).as("a resume joins the pass, never restarts a new one").isEqualTo(1);
            assertThat(after).doesNotHaveDuplicates();
            Set<String> union = new HashSet<>(before);
            union.addAll(after);
            assertThat(union).as("kill at %s misses nothing", kill).containsExactlyInAnyOrderElementsOf(keys);
            Set<String> revisited = new HashSet<>(before);
            revisited.retainAll(after);
            assertThat(revisited.size()).as("kill at %s re-visits at most one stride", kill)
                    .isLessThanOrEqualTo(CHECKPOINT);
        }
    }

    @Test
    void a_crash_inside_a_subtree_whose_name_prefixes_a_sibling_still_misses_nothing() throws IOException {
        // Path order visits the app/ subtree before the longer sibling name it prefixes (app.txt), although
        // app.txt sorts below app/nested in plain string order ('.' < '/'). A resume cursor of app/nested must
        // therefore not exclude the not-yet-visited app.txt - the comparison bug this test pins down.
        ArtifactStore store = store("anomaly");
        List<String> keys = List.of("publish/app/nested", "publish/app.txt", "publish/zebra");
        for (String key : keys) {
            store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
        }
        List<String> before = new ArrayList<>();
        assertThatThrownBy(() -> new StoreArtifactWalk(1, 1, Duration.ofMinutes(10), clock)
                .walk(store, "test", List.of("publish"), key -> {
                    before.add(key);
                    if (before.size() == 2) {
                        throw new IOException("crash");
                    }
                })).hasMessageContaining("crash");
        assertThat(before).containsExactly("publish/app/nested", "publish/app.txt");
        clock.advance(Duration.ofMinutes(11));
        List<String> after = new ArrayList<>();
        WalkPass resumed = new StoreArtifactWalk(1, 1, Duration.ofMinutes(10), clock)
                .walk(store, "test", List.of("publish"), after::add);
        assertThat(resumed.complete()).isTrue();
        assertThat(after).containsExactly("publish/app.txt", "publish/zebra");
    }
}
