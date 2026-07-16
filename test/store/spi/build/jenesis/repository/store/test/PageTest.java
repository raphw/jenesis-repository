package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ordered-paging contract of {@link ArtifactStore#page}, on the filesystem backend's bounded native override and
 * on the interface default (sort-and-filter over {@code list}) - both must answer identically: lexicographic order,
 * strictly after the boundary, capped at the limit, child containers and leaves alike, and empty for a missing
 * prefix or a non-positive limit.
 */
class PageTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    /** The filesystem store behind the interface's default {@code page}, so the fallback is what runs. */
    private static ArtifactStore fallback(ArtifactStore delegate) {
        return new ArtifactStore() {
            @Override
            public ArtifactStore scope(String tenant) {
                return delegate.scope(tenant);
            }

            @Override
            public boolean exists(String key) {
                return delegate.exists(key);
            }

            @Override
            public void read(String key, OutputStream out) throws IOException {
                delegate.read(key, out);
            }

            @Override
            public InputStream open(String key) throws IOException {
                return delegate.open(key);
            }

            @Override
            public void write(String key, InputStream in) throws IOException {
                delegate.write(key, in);
            }

            @Override
            public String writeBlob(InputStream in) throws IOException {
                return delegate.writeBlob(in);
            }

            @Override
            public long size(String key) throws IOException {
                return delegate.size(key);
            }

            @Override
            public void delete(String key) throws IOException {
                delegate.delete(key);
            }

            @Override
            public List<String> list(String prefix) {
                return delegate.list(prefix);
            }

            @Override
            public Optional<Versioned> readVersioned(String key) throws IOException {
                return delegate.readVersioned(key);
            }

            @Override
            public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
                return delegate.writeVersioned(key, content, expected);
            }
        };
    }

    private static List<String> page(ArtifactStore store, String prefix, String startAfter, int limit) {
        List<String> names = new ArrayList<>();
        store.page(prefix, startAfter, limit, names::add);
        return names;
    }

    @Test
    void pages_in_order_strictly_after_the_boundary_and_bounded_by_the_limit() throws IOException {
        ArtifactStore store = store();
        for (String name : List.of("c", "a", "e", "b", "d")) {
            store.writeVersioned("dir/" + name, name.getBytes(StandardCharsets.UTF_8), null);
        }
        for (ArtifactStore paged : List.of(store, fallback(store))) {
            assertThat(page(paged, "dir", "", 2)).containsExactly("a", "b");
            assertThat(page(paged, "dir", "b", 10)).containsExactly("c", "d", "e");
            assertThat(page(paged, "dir", "b", 1)).containsExactly("c");
            assertThat(page(paged, "dir", "e", 10)).isEmpty();
            assertThat(page(paged, "dir", "", 0)).isEmpty();
            assertThat(page(paged, "missing", "", 10)).isEmpty();
        }
    }

    @Test
    void a_child_that_prefixes_a_longer_sibling_name_pages_in_name_order() throws IOException {
        // Child-NAME order puts "banana" (a container) before "banana.txt" (a leaf), although the container's
        // raw keys (banana/...) sort past the leaf in plain key order ('.' < '/') - the contract every backend
        // must repair its native stream to, or a paging resume would silently drop the shorter child.
        ArtifactStore store = store();
        store.writeVersioned("dir/apple", new byte[0], null);
        store.writeVersioned("dir/banana/nested", new byte[0], null);
        store.writeVersioned("dir/banana.txt", new byte[0], null);
        store.writeVersioned("dir/cherry", new byte[0], null);
        for (ArtifactStore paged : List.of(store, fallback(store))) {
            assertThat(page(paged, "dir", "", 10)).containsExactly("apple", "banana", "banana.txt", "cherry");
            assertThat(page(paged, "dir", "", 2)).containsExactly("apple", "banana");
            assertThat(page(paged, "dir", "banana", 10)).containsExactly("banana.txt", "cherry");
        }
    }

    @Test
    void containers_and_leaves_page_alike_and_a_full_traversal_matches_list() throws IOException {
        ArtifactStore store = store();
        store.writeVersioned("dir/leaf", new byte[0], null);
        store.writeVersioned("dir/nested/child", new byte[0], null);
        store.writeVersioned("dir/other/child", new byte[0], null);
        for (ArtifactStore paged : List.of(store, fallback(store))) {
            List<String> names = new ArrayList<>();
            String startAfter = "";
            while (true) {
                List<String> batch = page(paged, "dir", startAfter, 1);
                if (batch.isEmpty()) {
                    break;
                }
                names.addAll(batch);
                startAfter = batch.getLast();
            }
            assertThat(names).containsExactlyElementsOf(store.list("dir"));
        }
    }
}
