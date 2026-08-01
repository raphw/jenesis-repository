package build.jenesis.repository.format.raw.test;

import build.jenesis.repository.format.raw.RawImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raw importer claims the {@code raw}/{@code generic} source formats and stores each asset content-addressed under
 * {@code /raw/...} exactly as a {@code PUT} would, whether or not the source path carries a leading slash.
 */
class RawImporterTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final RawImporter importer = new RawImporter();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void it_handles_the_generic_source_formats() {
        assertThat(importer.imports("raw")).isTrue();
        assertThat(importer.imports("generic")).isTrue();
        assertThat(importer.imports("maven")).isFalse();
    }

    @Test
    void an_imported_asset_is_stored_content_addressed_under_raw() throws IOException {
        byte[] body = "installer.bin".getBytes(StandardCharsets.UTF_8);
        importer.importArtifact("dir/file.txt", new ByteArrayInputStream(body), store);

        String key = publication.located("/raw/dir/file.txt").orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read(key, out);
        assertThat(out.toByteArray()).isEqualTo(body);

        importer.importArtifact("/other/x", new ByteArrayInputStream(new byte[]{1, 2}), store);
        assertThat(publication.located("/raw/other/x")).as("a leading slash is normalised").isPresent();
    }

    @Test
    void the_import_target_is_the_raw_serving_path_the_edge_gates_against() {
        ArtifactDescriptor relative = importer.importTarget("dir/x").orElseThrow();
        assertThat(relative.ecosystem()).as("a raw asset has no ecosystem layout").isEqualTo("raw");
        assertThat(relative.path()).as("the verbatim /raw/ serving path is the screen identity").isEqualTo("/raw/dir/x");

        ArtifactDescriptor rooted = importer.importTarget("/dir/x").orElseThrow();
        assertThat(rooted.ecosystem()).isEqualTo("raw");
        assertThat(rooted.path()).as("a leading slash is normalised to the same target").isEqualTo("/raw/dir/x");
    }
}
