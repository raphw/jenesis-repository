package build.jenesis.repository.format.raw.test;

import build.jenesis.repository.format.raw.RawImporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
        assertThat(importer.handles("raw")).isTrue();
        assertThat(importer.handles("generic")).isTrue();
        assertThat(importer.handles("maven")).isFalse();
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
    void an_imported_asset_is_screened_by_the_gate() throws IOException {
        // Guards RawImporter routing through Publication.publish rather than a raw link: a migrated asset the gate
        // quarantines is withheld from serving (held for review), and a rejected one links nothing - a revert to
        // link(storeBlob(content)) would import both un-screened.
        importer.importArtifact("gate-quarantine.bin",
                new ByteArrayInputStream("deny-listed".getBytes(StandardCharsets.UTF_8)), store);
        assertThat(publication.located("/raw/gate-quarantine.bin"))
                .as("a quarantined import is not served").isEmpty();
        assertThat(publication.located("/quarantine/raw/gate-quarantine.bin"))
                .as("but is held for review").isPresent();

        importer.importArtifact("gate-reject.bin",
                new ByteArrayInputStream("blocked".getBytes(StandardCharsets.UTF_8)), store);
        assertThat(publication.located("/raw/gate-reject.bin")).as("a rejected import links nothing").isEmpty();
    }
}
