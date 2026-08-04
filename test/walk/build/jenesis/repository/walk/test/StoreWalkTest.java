package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    /** Audit-27 A3-F1: the reference walk's depth descent used to self-recurse one call frame per key path segment,
     *  so a single deep publish key (a many-segment Maven groupId, a multi-segment OCI name - depth is client-planted
     *  and uncapped at the routing edge) overflowed the stack with a {@link StackOverflowError}, aborting the shared
     *  walk every background sweep drives (reconcile, inventory rebuild, retroactive-hold enforcement). The descent is
     *  now an explicit-stack traversal, so an arbitrarily deep key is walked to completion. A real filesystem cannot
     *  hold a {@value #DEEP} directory chain ({@code PATH_MAX}), so this drives an in-memory store whose only synthetic
     *  publish object is a single {@value #DEEP}-segment-deep key - far past any stack the old recursion could hold. */
    @Test
    void a_pathologically_deep_key_walks_without_overflowing_the_stack() {
        MemoryStore store = new MemoryStore();
        StringBuilder key = new StringBuilder("publish");
        for (int depth = 0; depth < DEEP; depth++) {
            key.append("/a");
        }
        key.append("/leaf");
        String deepKey = key.toString();
        store.seed(deepKey);

        List<String> visited = new ArrayList<>();
        assertThatCode(() -> walk(100, 1).walk(store, "test", List.of("publish"), visited::add))
                .as("a %d-segment-deep key must not overflow the stack (an iterative descent, not recursion)", DEEP)
                .doesNotThrowAnyException();
        assertThat(visited)
                .as("the deep key was actually reached and visited, the descent did not stop short")
                .containsExactly(deepKey);
    }

    /** Audit-27 A3-F1 order-equivalence: the iterative descent must visit exactly the keys, in exactly the path order,
     *  the former recursion produced. This mixed fixture stresses depth (a deep chain), width (a > {@code PAGE}
     *  sibling fan-out that forces multi-page paging inside one container) and multiple roots at once, and asserts the
     *  visited sequence is the one total path order - run under several segment counts, since the static range plan
     *  cuts the key space differently each time yet every cut must reassemble into the identical total order (the
     *  contiguous half-open ranges the seek-first + {@code to}-bounded descent walks). */
    @Test
    void the_iterative_descent_visits_a_mixed_deep_and_wide_fixture_in_the_identical_path_order() throws IOException {
        List<String> keys = new ArrayList<>(List.of(
                "publish/com/acme/app/1.0/app-1.0.jar",
                "publish/com/acme/app/1.0/app-1.0.pom",
                "publish/com/acme/app/2.0/app-2.0.jar",
                "npm/left/x",
                "npm/right/y"));
        StringBuilder deep = new StringBuilder("publish/deep");
        for (int level = 0; level < 60; level++) {
            deep.append("/n");
        }
        keys.add(deep + "/leaf");
        for (int index = 0; index <= 2500; index++) {           // a wide container past PAGE (1000): multi-page paging
            keys.add(String.format("publish/wide/pkg-%04d", index));
        }
        // The chosen names avoid the directory-vs-file prefix anomaly (no sibling is a strict prefix of another before
        // a separator), so the walk's path order coincides with natural string order - the expected total sequence.
        List<String> expected = keys.stream().sorted().toList();

        for (int segments : new int[] {1, 4, 16}) {
            MemoryStore store = new MemoryStore();
            for (String key : keys) {
                store.seed(key);
            }
            List<String> visited = new ArrayList<>();
            WalkPass pass = walk(50, segments).walk(store, "test", List.of("publish", "npm"), visited::add);
            assertThat(pass.complete()).as("the pass completes with %d segments", segments).isTrue();
            assertThat(visited)
                    .as("the iterative descent yields the identical path-order key sequence with %d segments", segments)
                    .containsExactlyElementsOf(expected);
        }
    }

    private static final int DEEP = 12_000;

    /** An in-memory {@link ArtifactStore} the depth and order tests drive so a synthetic key tree of any shape (a
     *  deep chain no filesystem could hold, a wide fan-out) is walked without touching disk. Objects live in a sorted
     *  map keyed by full object key; immediate-child enumeration is derived from that map, and the small-object
     *  compare-and-set is a version-token check - exactly what the walk needs to persist its manifest and segments. */
    private static final class MemoryStore implements ArtifactStore {

        private final NavigableMap<String, byte[]> objects = new TreeMap<>();
        private final Map<String, Long> tokens = new HashMap<>();
        private long counter;

        /** Seed a bare leaf object at {@code key} (empty body): the walk emits a key on {@link #exists}, never reading
         *  its content, so no body is needed to exercise the descent. */
        private void seed(String key) {
            objects.put(key, new byte[0]);
            tokens.put(key, ++counter);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            byte[] content = objects.get(key);
            return content == null ? Optional.empty() : Optional.of(new Versioned(content, tokens.get(key)));
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            Long current = tokens.get(key);
            if (expected == null ? current != null : !Objects.equals(current, expected)) {
                return false;   // the compare-and-set lost: the stored version no longer matches
            }
            objects.put(key, content);
            tokens.put(key, ++counter);
            return true;
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
            tokens.remove(key);
        }

        @Override
        public List<String> list(String prefix) {
            return children(prefix, "", Integer.MAX_VALUE);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            for (String child : children(prefix, startAfter, limit)) {
                consumer.accept(child);
            }
        }

        /** The immediate child names under {@code prefix}, lexicographic, strictly after {@code startAfter}, up to
         *  {@code limit} - the ordered-paging contract {@link ArtifactStore#page} documents, derived from the flat key
         *  map. */
        private List<String> children(String prefix, String startAfter, int limit) {
            String base = prefix.isEmpty() ? "" : prefix + "/";
            NavigableSet<String> names = new TreeSet<>();
            for (String key : objects.keySet()) {
                if (!base.isEmpty() && !key.startsWith(base)) {
                    continue;
                }
                String rest = key.substring(base.length());
                if (rest.isEmpty()) {
                    continue;   // the prefix itself is a stored leaf, not a child
                }
                int slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash));
            }
            List<String> result = new ArrayList<>();
            for (String name : names) {
                if (name.compareTo(startAfter) <= 0) {
                    continue;
                }
                if (result.size() >= limit) {
                    break;
                }
                result.add(name);
            }
            return result;
        }

        @Override
        public long size(String key) {
            byte[] content = objects.get(key);
            return content == null ? -1L : content.length;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public void read(String key, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream open(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String key, InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
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
