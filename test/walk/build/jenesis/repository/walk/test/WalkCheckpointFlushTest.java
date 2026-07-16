package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.ArtifactWalk;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code KeyVisitor.beforeCheckpoint} half of the delivery contract: the visitor's flush hook runs before
 * every durable cursor commit - each checkpoint stride and segment completion - and a flush that fails leaves the
 * previous cursor standing, so a consumer that buffers derived writes is never resumed past an item whose write
 * died in its buffer.
 */
class WalkCheckpointFlushTest {

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
            String key = "publish/" + String.format("%02d", index) + "/artifact";
            keys.add(key);
            store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
        }
        return keys;
    }

    @Test
    void the_visitor_flushes_before_every_cursor_commit_and_at_segment_end() throws IOException {
        ArtifactStore store = store();
        List<String> keys = seed(store, 12);
        List<String> visited = new ArrayList<>();
        List<String> flushed = new ArrayList<>();
        WalkPass pass = new StoreArtifactWalk(5, 1, Duration.ofMinutes(10), clock)
                .walk(store, "test", List.of("publish"), new ArtifactWalk.KeyVisitor() {
                    @Override
                    public void visit(String key) {
                        visited.add(key);
                    }

                    @Override
                    public void beforeCheckpoint(String cursor) {
                        assertThat(cursor).as("the cursor about to be committed is the last visited key")
                                .isEqualTo(visited.getLast());
                        flushed.add(cursor);
                    }
                });
        assertThat(pass.complete()).isTrue();
        assertThat(flushed).as("a flush before each stride commit (5, 10) and one at segment completion")
                .containsExactly(keys.get(4), keys.get(9), keys.get(11));
    }

    @Test
    void an_empty_segment_checkpoints_with_a_null_cursor() throws IOException {
        List<String> flushed = new ArrayList<>();
        WalkPass pass = new StoreArtifactWalk(5, 1, Duration.ofMinutes(10), clock)
                .walk(store(), "test", List.of("publish"), new ArtifactWalk.KeyVisitor() {
                    @Override
                    public void visit(String key) {
                    }

                    @Override
                    public void beforeCheckpoint(String cursor) {
                        flushed.add(cursor);
                    }
                });
        assertThat(pass.complete()).isTrue();
        assertThat(flushed).as("a segment that held no keys still completes, with nothing to flush behind")
                .containsExactly((String) null);
    }

    @Test
    void a_failed_flush_leaves_the_previous_cursor_standing_so_nothing_is_lost() throws IOException {
        ArtifactStore store = store();
        List<String> keys = seed(store, 7);
        List<String> durable = new ArrayList<>();

        // A buffering consumer whose first flush dies after the walk visited a full stride: were the cursor
        // committed before the flush, the resume would skip the five buffered-but-lost items forever.
        final class Buffering implements ArtifactWalk.KeyVisitor {
            private final List<String> buffer = new ArrayList<>();
            private boolean fail;

            private Buffering(boolean fail) {
                this.fail = fail;
            }

            @Override
            public void visit(String key) {
                buffer.add(key);
            }

            @Override
            public void beforeCheckpoint(String cursor) throws IOException {
                if (fail) {
                    fail = false;
                    throw new IOException("flush died");
                }
                durable.addAll(buffer);
                buffer.clear();
            }
        }

        assertThatThrownBy(() -> new StoreArtifactWalk(5, 1, Duration.ofMinutes(10), clock)
                .walk(store, "test", List.of("publish"), new Buffering(true)))
                .hasMessageContaining("flush died");
        assertThat(durable).as("nothing was flushed, so nothing may count as processed").isEmpty();
        assertThat(new StoreArtifactWalk(5, 1, Duration.ofMinutes(10), clock).segments(store, "test"))
                .as("the cursor never advanced past the unflushed stride")
                .allSatisfy(segment -> assertThat(segment.cursor()).isNull());

        clock.advance(Duration.ofMinutes(11));
        WalkPass resumed = new StoreArtifactWalk(5, 1, Duration.ofMinutes(10), clock)
                .walk(store, "test", List.of("publish"), new Buffering(false));
        assertThat(resumed.complete()).isTrue();
        assertThat(durable).as("the re-visit replays exactly what the dead flush lost")
                .containsExactlyElementsOf(keys);
    }
}
