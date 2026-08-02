package build.jenesis.repository.ui.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.ui.BrowseController;
import module org.junit.jupiter.api;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console browse controller's listing: it pages the immediate children of a browse path (never materialising a
 * possibly-millions-entry directory as one {@code List}), classifies folder-vs-artifact with a bounded one-element
 * probe (never a full subtree {@code list()} per child), and caps what one browse renders while flagging truncation.
 */
class BrowseControllerTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_browse_pages_children_and_probes_folders_without_a_full_directory_listing() throws IOException {
        // One artifact directly under com/example and one under a nested subfolder, so the browse must classify a
        // folder (nested/) vs an artifact (a-1.0.jar). The folder probe must be a bounded seek, not a full list().
        publication.link("/com/example/a-1.0.jar", store.writeBlob(
                new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8))));
        publication.link("/com/example/nested/b-1.0.jar", store.writeBlob(
                new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8))));

        CountingList counting = new CountingList(store);
        BrowseController controller = new BrowseController(counting);
        Model model = new ConcurrentModel();
        controller.browse("com/example", model);

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.getAttribute("entries");
        assertThat(entries).extracting(e -> e.get("name")).containsExactlyInAnyOrder("a-1.0.jar", "nested");
        assertThat(entries).filteredOn(e -> e.get("name").equals("nested")).singleElement()
                .satisfies(e -> assertThat(e.get("folder")).isEqualTo(true));
        assertThat(entries).filteredOn(e -> e.get("name").equals("a-1.0.jar")).singleElement()
                .satisfies(e -> assertThat(e.get("folder")).isEqualTo(false));
        assertThat(model.getAttribute("truncated")).isEqualTo(false);
        assertThat(counting.lists())
                .as("children are paged and folders probed by a bounded page - never a full list()").isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_directory_larger_than_the_render_cap_is_capped_and_flagged_truncated() throws IOException {
        String hash = store.writeBlob(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 1001; i++) {   // one past the render cap
            publication.link(String.format("/big/v%04d.jar", i), hash);
        }
        BrowseController controller = new BrowseController(store);
        Model model = new ConcurrentModel();
        controller.browse("big", model);

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.getAttribute("entries");
        assertThat(entries).as("the render is capped so a huge directory cannot OOM the console").hasSize(1000);
        assertThat(model.getAttribute("truncated")).as("and the cap is surfaced, not silent").isEqualTo(true);
    }

    /** A store decorator that counts {@code list(prefix)} calls and delegates {@code page(...)} to the real backend's
     *  efficient seek (never the default {@code page} that re-lists), so a test can prove the browse never full-lists. */
    private static final class CountingList implements ArtifactStore {

        private final ArtifactStore delegate;
        private int lists;

        private CountingList(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        private int lists() {
            return lists;
        }

        @Override
        public List<String> list(String prefix) {
            lists++;
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);
        }

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
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
