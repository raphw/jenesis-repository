package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.store.Trees;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The reusable {@link Trees#descend} deep-walk primitive, exercised directly (not only through
 * {@link StoreWalkTest}'s delegation): the extracted iterative descent every consumer is meant to share instead of
 * hand-rolling a tree walk. It confirms the three properties the primitive promises - iterative depth (an
 * arbitrarily deep key walks without a {@link StackOverflowError}), path-order over a mixed deep-and-wide fixture,
 * and the {@link Trees.Visitor} steering ({@code seek} / {@code ceiling} / {@code enters} / {@code emits}) that
 * confines a descent to a half-open key range - the exact contract {@code StoreArtifactWalk} drives its segments with.
 */
class TreesDescendTest {

    @Test
    void a_pathologically_deep_key_descends_without_overflowing_the_stack() throws IOException {
        MemoryStore store = new MemoryStore();
        StringBuilder key = new StringBuilder("root");
        for (int depth = 0; depth < 20_000; depth++) {
            key.append("/a");
        }
        key.append("/leaf");
        String deep = key.toString();
        store.seed(deep);

        List<String> visited = new ArrayList<>();
        assertThatCode(() -> Trees.descend(store, "root", visited::add))
                .as("a 20000-segment-deep key must not overflow the stack (iterative descent, not recursion)")
                .doesNotThrowAnyException();
        assertThat(visited).as("the deep key was reached and visited").containsExactly(deep);
    }

    @Test
    void a_mixed_deep_and_wide_fixture_is_visited_in_path_order() throws IOException {
        MemoryStore store = new MemoryStore();
        List<String> keys = new ArrayList<>(List.of(
                "root/com/acme/app/1.0/app-1.0.jar",
                "root/com/acme/app/1.0/app-1.0.pom",
                "root/com/acme/app/2.0/app-2.0.jar"));
        StringBuilder deep = new StringBuilder("root/deep");
        for (int level = 0; level < 200; level++) {
            deep.append("/n");
        }
        keys.add(deep + "/leaf");
        for (int index = 0; index <= 2500; index++) {   // a container wider than one page (Trees.PAGE): multi-page paging
            keys.add(String.format("root/wide/pkg-%04d", index));
        }
        for (String key : keys) {
            store.seed(key);
        }
        // Names chosen so no sibling is a strict prefix of another before a separator, so path order coincides with
        // natural string order - the expected total sequence.
        List<String> expected = keys.stream().sorted().toList();

        List<String> visited = new ArrayList<>();
        Trees.descend(store, "root", visited::add);
        assertThat(visited).containsExactlyElementsOf(expected);
    }

    @Test
    void a_bounded_visitor_confines_the_descent_to_a_half_open_range() throws IOException {
        MemoryStore store = new MemoryStore();
        for (String key : List.of("root/a/1", "root/a/2", "root/b/1", "root/c/1")) {
            store.seed(key);
        }
        // Confine to [root/a, root/c): seek to the range start, prune subtrees and stop paging at the ceiling, emit
        // only in-range leaves - the same steering StoreArtifactWalk's Worker supplies.
        List<String> visited = new ArrayList<>();
        Trees.descend(store, "root", new Trees.Visitor() {
            @Override
            public void visit(String key) {
                visited.add(key);
            }

            @Override
            public boolean emits(String key) {
                return Trees.order(key, "root/a") >= 0 && Trees.order(key, "root/c") < 0;
            }

            @Override
            public boolean enters(String prefix) {
                return Trees.order(prefix + "0", "root/a") > 0 && Trees.order(prefix + "/", "root/c") < 0;
            }

            @Override
            public String seek() {
                return "root/a";
            }

            @Override
            public String ceiling() {
                return "root/c";
            }
        });
        assertThat(visited)
                .as("only leaves in [root/a, root/c) are visited, in path order")
                .containsExactly("root/a/1", "root/a/2", "root/b/1");
    }

    /** A minimal in-memory {@link ArtifactStore} for driving {@link Trees#descend} over a synthetic key tree of any
     *  shape without touching disk: objects in a sorted map, immediate-child enumeration derived from it, and only the
     *  {@code exists} / {@code page} the descent consumes implemented (every other operation is unused here). */
    private static final class MemoryStore implements ArtifactStore {

        private final NavigableSet<String> keys = new TreeSet<>();

        private void seed(String key) {
            keys.add(key);
        }

        @Override
        public boolean exists(String key) {
            return keys.contains(key);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            String base = prefix.isEmpty() ? "" : prefix + "/";
            NavigableSet<String> names = new TreeSet<>();
            for (String key : keys) {
                if (!base.isEmpty() && !key.startsWith(base)) {
                    continue;
                }
                String rest = key.substring(base.length());
                if (rest.isEmpty()) {
                    continue;
                }
                int slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash));
            }
            int emitted = 0;
            for (String name : names) {
                if (name.compareTo(startAfter) <= 0) {
                    continue;
                }
                if (emitted++ >= limit) {
                    break;
                }
                consumer.accept(name);
            }
        }

        @Override
        public List<String> list(String prefix) {
            List<String> names = new ArrayList<>();
            page(prefix, "", Integer.MAX_VALUE, names::add);
            return names;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            return Optional.empty();
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size(String key) {
            return exists(key) ? 0L : -1L;
        }

        @Override
        public void delete(String key) {
            keys.remove(key);
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
}
