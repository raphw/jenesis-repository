package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.server.AssetCatalog;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishedAssets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The publish/ pointer-tree walk now lives once in {@link PublishedAssets}; the server's {@link AssetCatalog}
 * catalogue and the console's NDJSON asset export ({@code BrowseController}) both delegate to it. This pins that the
 * two callers see one and the same enumeration: the format-enriched {@link AssetCatalog.Asset}s and the neutral
 * {@link PublishedAssets.Entry}s the console renders carry identical path / size / SHA-256, in identical order, with
 * the {@code /quarantine} review subtree excluded from both - so the two surfaces can never disagree about what is
 * published.
 */
class PublishedAssetsConsistencyTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private AssetCatalog catalog;
    private PublishedAssets assets;

    @BeforeEach
    void seed() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem", key -> root.toString())
                .scope("default").scope("default");
        publication = new Publication(store);

        link("/maven/com/acme/app/1.0/app-1.0.pom",
                "<project><artifactId>app</artifactId><version>1.0</version></project>");
        link("/maven/com/acme/app/2.0/app-2.0.pom",
                "<project><artifactId>app</artifactId><version>2.0</version></project>");
        link("/raw/tools/installer.bin", "a signed installer");
        // A file-vs-directory sibling pair, the case the emission-order cursor exists for.
        link("/raw/data/payload.bin", "in a subfolder");
        link("/raw/data.txt", "a sibling file");
        // A quarantined hold - stored but not served, so neither surface may enumerate it.
        link("/quarantine/maven/com/evil/1.0/evil-1.0.pom", "held");

        RepositoryFormat maven = new MavenFormat();
        RepositoryFormat raw = new RawishFormat();
        Function<String, Optional<RepositoryFormat>> owner = path ->
                Stream.of(maven, raw).filter(format -> format.handles(path)).findFirst();
        catalog = new AssetCatalog(store, owner);
        assets = new PublishedAssets(store);
    }

    @Test
    void the_catalogue_and_the_shared_walk_enumerate_the_same_pointers_in_the_same_order() throws IOException {
        List<AssetCatalog.Asset> catalogued = catalog.page(null, 100).assets();

        // The store-level walk the console's NDJSON export delegates to, collected the same way the export writes it.
        List<PublishedAssets.Entry> walked = new ArrayList<>();
        assets.walk(null, Integer.MAX_VALUE, walked::add);

        assertThat(walked).extracting(PublishedAssets.Entry::path)
                .as("both surfaces walk the same publish/ tree in the same emission order")
                .containsExactlyElementsOf(catalogued.stream().map(AssetCatalog.Asset::path).toList())
                .doesNotContain("/quarantine/maven/com/evil/1.0/evil-1.0.pom");

        // Path, size and SHA-256 - the facts the console renders - match the catalogue entry for entry.
        for (int index = 0; index < catalogued.size(); index++) {
            AssetCatalog.Asset asset = catalogued.get(index);
            PublishedAssets.Entry entry = walked.get(index);
            assertThat(entry.path()).isEqualTo(asset.path());
            assertThat(entry.size()).isEqualTo(asset.size());
            assertThat(entry.sha256()).isEqualTo(asset.sha256());
        }
    }

    private void link(String path, String content) throws IOException {
        publication.link(path, publication.storeBlob(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
    }

    /** A minimal coordinate-less format owning {@code /raw/...} (the real {@code RawFormat} is not exported here). */
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
