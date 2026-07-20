package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared rebuild pass - the walk half of the two-route derived-metadata contract made runnable: one enumeration
 * of the pointer roots feeds every {@link WalkConsumer} with retained-artifact notifications. Exactly-once delivery
 * per pass with the documented descriptor richness (request path under {@code publish/}, raw store key elsewhere,
 * hash always, blob size or {@code -1} for a torn pointer); at-least-once across an injected crash-resume with
 * idempotency absorbing the replay; a consumer enabled late rebuilding its whole view purely from the walk; the
 * pass hooks bracketing delivery even over an empty store; and the guard rails (reserved roots refused, no
 * consumers means nothing enumerated).
 */
class RebuildPassTest {

    private static final int CHECKPOINT = 5;

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store(String name) {
        Path scoped = root.resolve(name);
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? scoped.toString() : null);
    }

    private StoreArtifactWalk walk() {
        return new StoreArtifactWalk(CHECKPOINT, 1, Duration.ofMinutes(10), clock);
    }

    /** Store real content and point {@code publish<path>} at it, the way {@code Publication} lays pointers out. */
    private static String publish(ArtifactStore store, String path, String content) throws IOException {
        String hash = store.writeBlob(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        store.writeVersioned("publish" + path, hash.getBytes(StandardCharsets.UTF_8), null);
        return hash;
    }

    /** A consumer recording everything it is handed: the event order, and an idempotent path-to-hash view. */
    private static final class Recording implements WalkConsumer {

        final List<String> events = new ArrayList<>();
        final List<ArtifactDescriptor> retained = new ArrayList<>();
        final Map<String, String> derived = new HashMap<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
            events.add("retained:" + artifact.path());
            retained.add(artifact);
            derived.put(artifact.path(), artifact.hash());
        }

        @Override
        public void onPassStarted(WalkPass pass) {
            events.add("started:" + pass.generation());
        }

        @Override
        public void onPassCompleted(WalkPass pass) {
            events.add("completed:" + pass.generation());
        }
    }

    @Test
    void a_pass_delivers_every_pointer_exactly_once_to_every_consumer_between_the_hooks() throws IOException {
        ArtifactStore store = store("exactly-once");
        String first = publish(store, "/maven/app-1.0.jar", "first payload");
        String second = publish(store, "/maven/app-1.1.jar", "second one");
        // Sidecar-shaped leaves must never be delivered: not a bare hash, or far too large to be a pointer.
        store.writeVersioned("publish/maven/notes", "2026-07-16T00:00:00Z false".getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("publish/maven/oversized", new byte[2048], null);
        Recording one = new Recording(), two = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, List.of("publish"), List.of(one, two));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        for (Recording consumer : List.of(one, two)) {
            assertThat(consumer.events).as("started brackets the first delivery, completed the last")
                    .startsWith("started:1").endsWith("completed:1");
            assertThat(consumer.derived).containsOnlyKeys("/maven/app-1.0.jar", "/maven/app-1.1.jar");
            assertThat(consumer.derived).containsEntry("/maven/app-1.0.jar", first)
                    .containsEntry("/maven/app-1.1.jar", second);
            assertThat(consumer.retained).as("exactly once per pointer, no sidecar or oversized leaf").hasSize(2);
            assertThat(consumer.retained.getFirst().size()).isEqualTo("first payload".length());
            assertThat(consumer.retained.getFirst().path()).as("the publish/ namespace maps to the request path")
                    .isEqualTo("/maven/app-1.0.jar");
        }
    }

    @Test
    void a_crash_mid_pass_resumes_and_idempotency_absorbs_the_replayed_stride() throws IOException {
        ArtifactStore store = store("crash");
        Map<String, String> expected = new HashMap<>();
        for (char letter = 'a'; letter <= 'z'; letter++) {
            expected.put("/" + letter + "/artifact", publish(store, "/" + letter + "/artifact", "content " + letter));
        }
        Recording consumer = new Recording();
        List<String> before = new ArrayList<>();
        WalkConsumer fatal = new WalkConsumer() {
            @Override
            public String name() {
                return "fatal";
            }

            @Override
            public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                before.add(artifact.path());
                if (before.size() == 13) {
                    throw new IOException("crash mid-pass");
                }
            }
        };
        assertThatThrownBy(() -> RebuildPass.run(walk(), store, List.of("publish"), List.of(consumer, fatal)))
                .hasMessageContaining("crash mid-pass");
        clock.advance(Duration.ofMinutes(11));

        Optional<WalkPass> resumed = RebuildPass.run(walk(), store, List.of("publish"), List.of(consumer));

        assertThat(resumed).hasValueSatisfying(pass -> {
            assertThat(pass.complete()).isTrue();
            assertThat(pass.generation()).as("a resume joins the pass, never restarts a new one").isEqualTo(1);
        });
        assertThat(consumer.derived).as("at-least-once across the crash, idempotent upsert converging on the truth")
                .containsExactlyInAnyOrderEntriesOf(expected);
        Set<String> paths = new HashSet<>();
        for (ArtifactDescriptor artifact : consumer.retained) {
            paths.add(artifact.path());
        }
        assertThat(paths).as("no pointer is ever missed").hasSize(expected.size());
        assertThat(consumer.retained.size() - expected.size()).as("at most one checkpoint stride is replayed")
                .isLessThanOrEqualTo(CHECKPOINT);
    }

    @Test
    void a_consumer_enabled_late_rebuilds_its_whole_view_purely_from_the_walk() throws IOException {
        ArtifactStore store = store("late");
        // The history happened long before the plugin existed: artifacts published, one of them removed again.
        Map<String, String> expected = new HashMap<>();
        expected.put("/npm/left-pad-1.0.tgz", publish(store, "/npm/left-pad-1.0.tgz", "left pad"));
        expected.put("/pypi/requests-2.0.whl", publish(store, "/pypi/requests-2.0.whl", "requests"));
        publish(store, "/npm/gone-0.1.tgz", "removed again");
        store.delete("publish/npm/gone-0.1.tgz");
        Recording late = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, List.of("publish"), List.of(late));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(late.derived).as("the walk alone rebuilds the full view - and only of what is still retained")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void an_empty_store_still_fires_started_then_completed() throws IOException {
        ArtifactStore store = store("empty");
        Recording consumer = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, List.of("publish"), List.of(consumer));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(consumer.events).as("a rebuild from an empty truth is still a rebuild: reset, then commit empty")
                .containsExactly("started:1", "completed:1");
    }

    @Test
    void a_blobs_namespace_root_delivers_the_raw_key_and_a_torn_pointer_a_negative_size() throws IOException {
        ArtifactStore store = store("raw-keys");
        String served = publish(store, "/kept", "kept bytes");
        store.writeVersioned("npm/lodash/-/lodash-4.17.21.tgz", served.getBytes(StandardCharsets.UTF_8), null);
        String missing = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        store.writeVersioned("npm/lodash/-/lodash-4.17.20.tgz", missing.getBytes(StandardCharsets.UTF_8), null);
        Recording consumer = new Recording();

        RebuildPass.run(walk(), store, List.of("publish", "npm"), List.of(consumer));

        assertThat(consumer.derived).containsOnlyKeys(
                "/kept", "npm/lodash/-/lodash-4.17.21.tgz", "npm/lodash/-/lodash-4.17.20.tgz");
        ArtifactDescriptor torn = consumer.retained.stream()
                .filter(artifact -> artifact.path().equals("npm/lodash/-/lodash-4.17.20.tgz"))
                .findFirst().orElseThrow();
        assertThat(torn.hash()).isEqualTo(missing);
        assertThat(torn.size()).as("a pointer whose blob is missing is delivered as the torn state it is")
                .isNegative();
        ArtifactDescriptor linked = consumer.retained.stream()
                .filter(artifact -> artifact.path().equals("npm/lodash/-/lodash-4.17.21.tgz"))
                .findFirst().orElseThrow();
        assertThat(linked.size()).isEqualTo("kept bytes".length());
    }

    @Test
    void a_rebuild_never_reinstates_a_withheld_or_quarantined_pointer_into_the_index() throws IOException {
        ArtifactStore store = store("withheld");
        // A served artifact, an artifact retracted after the fact (a fresh advisory against bytes that served for
        // months - pointer and blob both intact), and a pointer the gate diverted to the quarantine review subtree.
        String served = publish(store, "/maven/app-1.0.jar", "served payload");
        publish(store, "/maven/app-1.1.jar", "later flagged");
        publish(store, "/quarantine/maven/held-1.0.jar", "held for review");
        // The withhold screen the deployment runs: app-1.1 has been retracted from serving though its pointer stands.
        Publication screened = new Publication(store, List.of(new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                return path.equals("/maven/app-1.1.jar");
            }
        }));
        Recording consumer = new Recording();

        Optional<WalkPass> pass = RebuildPass.run(walk(), store, screened, List.of("publish"), List.of(consumer));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(consumer.derived).as("a rebuild yields exactly what a GET would - never a withheld or "
                        + "quarantine-review pointer, so neither reappears in an index the pass rebuilds")
                .containsOnlyKeys("/maven/app-1.0.jar")
                .containsEntry("/maven/app-1.0.jar", served);
        assertThat(consumer.derived).as("the retracted-after-advisory artifact is not reinstated")
                .doesNotContainKey("/maven/app-1.1.jar");
        assertThat(consumer.derived).as("no phantom index entry for a quarantine-review pointer")
                .doesNotContainKey("/quarantine/maven/held-1.0.jar");
    }

    @Test
    void reserved_roots_are_refused_and_no_consumer_means_nothing_is_enumerated() throws IOException {
        ArtifactStore store = store("guards");
        publish(store, "/artifact", "bytes");
        for (String reserved : List.of("blobs", "gc", "walks", " ")) {
            assertThatThrownBy(() -> RebuildPass.run(walk(), store, List.of(reserved), List.of(new Recording())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RebuildPass.run(walk(), store, List.of(), List.of(new Recording())))
                .isInstanceOf(IllegalArgumentException.class);

        StoreArtifactWalk walk = walk();
        assertThat(RebuildPass.run(walk, store, List.of("publish"), List.of())).isEmpty();
        assertThat(walk.pass(store, RebuildPass.CONSUMER)).as("no consumer, no pass state touched").isEmpty();
    }
}
