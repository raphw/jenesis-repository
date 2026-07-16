package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The core walk contract over a real filesystem store: one total lexicographic order across roots and segments,
 * every key exactly once per pass, pass state durable under {@code walks/<consumer>/}, generation turnover once a
 * pass completes, and the ServiceLoader provider resolution with its {@code jenesis.repository.walk} selection.
 */
class StoreWalkTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private StoreArtifactWalk walk(int checkpoint, int segments) {
        return new StoreArtifactWalk(checkpoint, segments, Duration.ofMinutes(10), clock);
    }

    private static void seed(ArtifactStore store, List<String> keys) throws IOException {
        for (String key : keys) {
            store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
        }
    }

    @Test
    void visits_every_key_exactly_once_in_lexicographic_order() throws IOException {
        ArtifactStore store = store();
        List<String> keys = List.of(
                "publish/com/acme/app/1.0/app-1.0.jar",
                "publish/com/acme/app/1.0/app-1.0.pom",
                "publish/com/acme/app/maven-metadata.xml",
                "publish/com/zeta/lib/2.0/lib-2.0.jar",
                "publish/org/first/a",
                "publish/org/second/b");
        seed(store, keys);
        List<String> visited = new ArrayList<>();
        WalkPass pass = walk(2, 4).walk(store, "test", List.of("publish"), visited::add);
        assertThat(pass.complete()).isTrue();
        assertThat(pass.generation()).isEqualTo(1);
        assertThat(pass.done()).isEqualTo(pass.segments());
        assertThat(visited).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(visited).containsExactlyElementsOf(keys.stream().sorted().toList());
    }

    @Test
    void roots_walk_in_sorted_order_within_one_total_key_order() throws IOException {
        ArtifactStore store = store();
        seed(store, List.of("publish/x/1", "npm/lodash/4", "npm/react/19"));
        List<String> visited = new ArrayList<>();
        WalkPass pass = walk(10, 4).walk(store, "test", List.of("publish", "npm"), visited::add);
        assertThat(pass.complete()).isTrue();
        assertThat(visited).containsExactly("npm/lodash/4", "npm/react/19", "publish/x/1");
    }

    @Test
    void a_completed_pass_turns_the_generation_and_the_next_pass_sees_new_content() throws IOException {
        ArtifactStore store = store();
        seed(store, List.of("publish/a/1"));
        StoreArtifactWalk walk = walk(10, 2);
        assertThat(walk.walk(store, "test", List.of("publish"), key -> {
        }).generation()).isEqualTo(1);
        seed(store, List.of("publish/b/2"));
        List<String> visited = new ArrayList<>();
        WalkPass second = walk.walk(store, "test", List.of("publish"), visited::add);
        assertThat(second.generation()).isEqualTo(2);
        assertThat(second.complete()).isTrue();
        assertThat(visited).containsExactly("publish/a/1", "publish/b/2");
        assertThat(walk.pass(store, "test")).hasValueSatisfying(pass -> {
            assertThat(pass.generation()).isEqualTo(2);
            assertThat(pass.complete()).isTrue();
        });
    }

    @Test
    void pass_state_lives_in_the_store_under_the_consumer_scope() throws IOException {
        ArtifactStore store = store();
        seed(store, List.of("publish/a/1"));
        StoreArtifactWalk walk = walk(10, 2);
        assertThat(walk.pass(store, "test")).as("no pass was ever started").isEmpty();
        assertThat(walk.segments(store, "test")).isEmpty();
        walk.walk(store, "test", List.of("publish"), key -> {
        });
        assertThat(store.exists("walks/test/manifest")).isTrue();
        assertThat(store.list("walks/test/segments")).isNotEmpty();
    }

    @Test
    void a_consumer_name_that_could_escape_its_scope_is_refused() {
        assertThatThrownBy(() -> walk(10, 2).walk(store(), "a/b", List.of("publish"), key -> {
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_empty_root_completes_with_nothing_visited() throws IOException {
        List<String> visited = new ArrayList<>();
        WalkPass pass = walk(10, 4).walk(store(), "test", List.of("publish"), visited::add);
        assertThat(pass.complete()).isTrue();
        assertThat(visited).isEmpty();
    }

    @Test
    void the_provider_resolves_the_store_walk_and_honours_the_exclusive_selection() {
        assertThat(WalkProvider.installed()).isTrue();
        assertThat(WalkProvider.resolve(key -> null)).isPresent();
        Features.configure(key -> "jenesis.repository.walk".equals(key) ? "other" : null);
        try {
            assertThat(WalkProvider.resolve(key -> null))
                    .as("an explicit selection of another implementation skips this one").isEmpty();
        } finally {
            Features.reset();
        }
    }

    @Test
    void the_provider_reads_its_settings_and_rejects_garbage_loudly() {
        assertThat(WalkProvider.resolve(key -> "jenesis.walk.checkpoint".equals(key) ? "500" : null)).isPresent();
        assertThatThrownBy(() -> WalkProvider.resolve(
                key -> "jenesis.walk.checkpoint".equals(key) ? "many" : null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
