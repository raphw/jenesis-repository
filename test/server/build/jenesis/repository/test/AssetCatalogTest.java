package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.server.AssetCatalog;
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The publication-pointer walk behind {@code /api/assets}: it enumerates every published asset in the store's stable
 * lexicographic order, carrying each one's path, size, SHA-256 (straight from the pointer) and its owning format's
 * coordinate; it pages by an opaque path cursor that resumes exactly after the last asset; and it never surfaces the
 * {@code /quarantine} review subtree.
 */
class AssetCatalogTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private AssetCatalog catalog;

    private byte[] pomOne;
    private byte[] pomTwo;
    private byte[] rawBytes;

    @BeforeEach
    void seed() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem", key -> root.toString())
                .scope("default").scope("default");
        publication = new Publication(store);

        pomOne = "<project><artifactId>app</artifactId><version>1.0</version></project>".getBytes(StandardCharsets.UTF_8);
        pomTwo = "<project><artifactId>app</artifactId><version>2.0</version></project>".getBytes(StandardCharsets.UTF_8);
        rawBytes = "a signed installer".getBytes(StandardCharsets.UTF_8);

        link("/maven/com/acme/app/1.0/app-1.0.pom", pomOne);
        link("/maven/com/acme/app/2.0/app-2.0.pom", pomTwo);
        link("/raw/tools/installer.bin", rawBytes);
        // A quarantined hold - stored but not served, so it must never appear in the enumeration.
        link("/quarantine/maven/com/evil/1.0/evil-1.0.pom", "held".getBytes(StandardCharsets.UTF_8));

        RepositoryFormat maven = new MavenFormat();
        RepositoryFormat raw = new RawishFormat();
        Function<String, Optional<RepositoryFormat>> owner = path ->
                Stream.of(maven, raw).filter(format -> format.handles(path)).findFirst();
        catalog = new AssetCatalog(store, owner);
    }

    @Test
    void it_enumerates_published_assets_with_pointer_metadata_in_order() throws IOException {
        AssetCatalog.Page page = catalog.page(null, 100);

        assertThat(page.assets()).extracting(AssetCatalog.Asset::path).containsExactly(
                "/maven/com/acme/app/1.0/app-1.0.pom",
                "/maven/com/acme/app/2.0/app-2.0.pom",
                "/raw/tools/installer.bin");
        assertThat(page.cursor()).isNull();

        AssetCatalog.Asset first = page.assets().get(0);
        assertThat(first.size()).isEqualTo(pomOne.length);
        assertThat(first.sha256()).isEqualTo(sha256(pomOne));
        assertThat(first.format()).isEqualTo("maven");
        assertThat(first.ecosystem()).isEqualTo(MavenFormat.ECOSYSTEM);
        assertThat(first.coordinate()).isEqualTo("com.acme:app");
        assertThat(first.version()).isEqualTo("1.0");

        AssetCatalog.Asset rawAsset = page.assets().get(2);
        assertThat(rawAsset.format()).isEqualTo("raw");
        assertThat(rawAsset.size()).isEqualTo(rawBytes.length);
        assertThat(rawAsset.sha256()).isEqualTo(sha256(rawBytes));
        // A coordinate-less format carries a format name but no coordinate.
        assertThat(rawAsset.coordinate()).isNull();
        assertThat(rawAsset.ecosystem()).isNull();
    }

    @Test
    void it_pages_by_cursor_and_resumes_after_the_last_asset() throws IOException {
        AssetCatalog.Page first = catalog.page(null, 2);
        assertThat(first.assets()).extracting(AssetCatalog.Asset::path).containsExactly(
                "/maven/com/acme/app/1.0/app-1.0.pom",
                "/maven/com/acme/app/2.0/app-2.0.pom");
        assertThat(first.cursor()).isEqualTo("maven/com/acme/app/2.0/app-2.0.pom");

        AssetCatalog.Page second = catalog.page(first.cursor(), 2);
        assertThat(second.assets()).extracting(AssetCatalog.Asset::path)
                .containsExactly("/raw/tools/installer.bin");
        assertThat(second.cursor()).isNull();
    }

    @Test
    void a_single_asset_page_walks_the_whole_repository() throws IOException {
        List<String> walked = new java.util.ArrayList<>();
        String cursor = null;
        do {
            AssetCatalog.Page page = catalog.page(cursor, 1);
            page.assets().forEach(asset -> walked.add(asset.path()));
            cursor = page.cursor();
        } while (cursor != null);

        assertThat(walked).containsExactly(
                "/maven/com/acme/app/1.0/app-1.0.pom",
                "/maven/com/acme/app/2.0/app-2.0.pom",
                "/raw/tools/installer.bin");
    }

    private void link(String path, byte[] content) throws IOException {
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(content)));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** A minimal coordinate-less format for the raw path (the real {@code RawFormat} is not exported to this test
     *  module): it owns {@code /raw/...} and carries no {@link build.jenesis.repository.format.ArtifactLayout}. */
    private static final class RawishFormat implements RepositoryFormat {

        @Override
        public String name() {
            return "raw";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/raw/");
        }

        @Override
        public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
            exchange.respond(404);
        }
    }
}
