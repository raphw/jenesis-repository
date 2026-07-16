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

/**
 * The concurrent-publication visibility contract: a key published mid-pass ahead of the walk's position is seen by
 * this pass; one published behind it is not lost but guaranteed by the next pass - the documented eventual
 * consistency, with steady-state freshness owned by publication events, not walk latency.
 */
class WalkVisibilityTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void a_mid_pass_publish_is_seen_this_pass_when_ahead_and_by_the_next_pass_when_behind() throws IOException {
        ArtifactStore store = store();
        for (String key : List.of("publish/a/1", "publish/m/1", "publish/z/1")) {
            store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
        }
        StoreArtifactWalk walk = new StoreArtifactWalk(1, 1, Duration.ofMinutes(10), clock);
        List<String> first = new ArrayList<>();
        WalkPass pass = walk.walk(store, "test", List.of("publish"), key -> {
            first.add(key);
            if (key.equals("publish/m/1")) {
                store.writeVersioned("publish/a/0", new byte[0], null); // behind the cursor
                store.writeVersioned("publish/z/0", new byte[0], null); // ahead of the cursor
            }
        });
        assertThat(pass.complete()).isTrue();
        assertThat(first).contains("publish/z/0");
        assertThat(first).doesNotContain("publish/a/0");
        List<String> second = new ArrayList<>();
        walk.walk(store, "test", List.of("publish"), second::add);
        assertThat(second).as("what this pass passed by, the next pass guarantees").contains("publish/a/0");
    }
}
