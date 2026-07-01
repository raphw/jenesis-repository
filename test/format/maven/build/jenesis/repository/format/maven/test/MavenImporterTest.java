package build.jenesis.repository.format.maven.test;

import build.jenesis.repository.format.maven.MavenImporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven importer claims the {@code maven}/{@code maven2} source formats, publishes each imported artifact under
 * {@code /maven/...} so it resolves like a local deploy, and skips a {@code maven-metadata.xml} asset since the
 * metadata is regenerated on read rather than copied.
 */
class MavenImporterTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final MavenImporter importer = new MavenImporter();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void it_handles_the_maven_source_formats() {
        assertThat(importer.handles("maven")).isTrue();
        assertThat(importer.handles("maven2")).isTrue();
        assertThat(importer.handles("raw")).isFalse();
    }

    @Test
    void an_imported_jar_is_published_and_metadata_is_skipped() throws IOException {
        importer.importArtifact("org/example/lib/1.0/lib-1.0.jar",
                new ByteArrayInputStream("jar".getBytes(StandardCharsets.UTF_8)), store);
        assertThat(publication.located("/maven/org/example/lib/1.0/lib-1.0.jar")).isPresent();

        importer.importArtifact("org/example/lib/maven-metadata.xml",
                new ByteArrayInputStream("<metadata/>".getBytes(StandardCharsets.UTF_8)), store);
        assertThat(publication.located("/maven/org/example/lib/maven-metadata.xml"))
                .as("derived metadata is not imported").isEmpty();
    }
}
