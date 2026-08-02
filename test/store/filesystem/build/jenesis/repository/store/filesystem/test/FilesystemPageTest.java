package build.jenesis.repository.store.filesystem.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filesystem store's native {@code page} override exercised directly over a seeded {@code @TempDir}: the bounded
 * capped-{@code TreeSet} scan returns the lexicographically smallest names past the cursor in order (never buffering
 * the whole directory the way {@code list} would), the {@code startAfter} cursor resumes strictly after a name so
 * repeated pages traverse a child set without overlap or gap, a non-positive {@code limit} emits nothing, and the same
 * in-flight {@code .upload*.tmp} filter as {@code list} hides an atomic write's half-written temp file - even when that
 * temp file's leading {@code '.'} would otherwise sort it into the very front of the page. The default-implementation
 * paths (sort {@code list} and filter) are covered by {@code list}'s own test; this pins the filesystem override the
 * shared artifact walk resumes through, which {@code list}/{@code gc} only reach indirectly today.
 */
class FilesystemPageTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Collect the page {@code store.page} streams to its consumer, in the order it emits them. */
    private List<String> page(String prefix, String startAfter, int limit) {
        List<String> emitted = new ArrayList<>();
        store.page(prefix, startAfter, limit, emitted::add);
        return emitted;
    }

    @Test
    void page_returns_the_bounded_smallest_names_past_the_cursor_in_lexicographic_order() throws IOException {
        // Seed in reverse so the capped TreeSet must evict a larger name it already holds (its pollLast branch) as a
        // smaller one is scanned later - however the directory happens to iterate, the bounded page is the smallest set.
        for (int index = 9; index >= 0; index--) {
            store.write("d/n" + index, bytes("x"));
        }

        assertThat(page("d", "", 3))
                .as("the first page is the three smallest names, in order")
                .containsExactly("n0", "n1", "n2");
        assertThat(page("d", "n2", 3))
                .as("the next page resumes strictly after the cursor")
                .containsExactly("n3", "n4", "n5");
        assertThat(page("d", "n8", 3))
                .as("a short final page stops at the last name rather than padding the limit")
                .containsExactly("n9");
        assertThat(page("d", "n9", 3))
                .as("a cursor at or past the last name pages empty")
                .isEmpty();
    }

    @Test
    void page_resumes_strictly_after_the_cursor_excluding_the_cursor_name_itself() throws IOException {
        store.write("s/apple", bytes("1"));
        store.write("s/banana", bytes("2"));
        store.write("s/cherry", bytes("3"));

        assertThat(page("s", "", 10))
                .as("the empty cursor starts from the beginning")
                .containsExactly("apple", "banana", "cherry");
        assertThat(page("s", "banana", 10))
                .as("the cursor name is excluded (strictly-after), only greater names follow")
                .containsExactly("cherry");
        assertThat(page("s", "aardvark", 10))
                .as("a cursor below the first name includes every name")
                .containsExactly("apple", "banana", "cherry");
    }

    @Test
    void page_hides_an_in_flight_upload_temp_file_even_when_it_would_sort_to_the_front() throws IOException {
        store.write("u/a", bytes("1"));
        store.write("u/z", bytes("2"));
        // A half-written atomic-write spool file: its leading '.' sorts it before every real name, so a bounded page
        // that did not filter it would surface it first - a concurrent listing must never page out an in-flight temp.
        Files.createFile(root.resolve("u").resolve(".upload98765.tmp"));

        assertThat(page("u", "", 10))
                .as("the in-flight temp file never pages out")
                .containsExactly("a", "z");
        assertThat(page("u", "", 1))
                .as("a limit-1 page returns the first real name, not the front-sorting temp file")
                .containsExactly("a");
    }

    @Test
    void page_with_a_non_positive_limit_emits_nothing() throws IOException {
        store.write("z/a", bytes("1"));

        assertThat(page("z", "", 0)).as("a zero limit pages nothing").isEmpty();
        assertThat(page("z", "", -5)).as("a negative limit pages nothing").isEmpty();
    }

    @Test
    void page_of_an_absent_prefix_is_empty() {
        assertThat(page("missing", "", 10))
                .as("a prefix that is not a directory pages empty, mirroring list")
                .isEmpty();
    }
}
