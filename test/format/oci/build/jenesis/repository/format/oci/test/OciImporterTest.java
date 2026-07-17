package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.oci.OciImporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OCI importer claims the {@code docker}/{@code oci} source formats and stores each asset by its {@code sha256}
 * digest exactly as a push would: a manifest lands as a blob plus a {@code oci/types/<hex>} media-type sidecar (read
 * from the manifest's own {@code mediaType}) and, when referenced by a tag, a tag pointer; a layer lands as a blob.
 */
class OciImporterTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final OciImporter importer = new OciImporter();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String read(String key) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read(key, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void it_handles_the_docker_source_formats() {
        assertThat(importer.handles("docker")).isTrue();
        assertThat(importer.handles("oci")).isTrue();
        assertThat(importer.handles("raw")).isFalse();
    }

    @Test
    void a_manifest_asset_lands_as_a_blob_with_a_type_sidecar_and_tag_pointer() throws IOException {
        String type = "application/vnd.oci.image.manifest.v1+json";
        byte[] manifest = ("{\"mediaType\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        importer.importArtifact("v2/app/manifests/1.0", new ByteArrayInputStream(manifest), store);

        assertThat(store.exists("blobs/" + hex)).isTrue();
        assertThat(read("oci/types/" + hex)).isEqualTo(type);
        assertThat(store.readVersioned("oci/app/tags/1.0")).isPresent();
        assertThat(new String(store.readVersioned("oci/app/tags/1.0").orElseThrow().content(), StandardCharsets.UTF_8))
                .isEqualTo("sha256:" + hex);
    }

    @Test
    void a_blob_asset_lands_content_addressed() throws IOException {
        byte[] layer = "layer-bytes".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(layer);

        importer.importArtifact("v2/app/blobs/sha256:" + hex, new ByteArrayInputStream(layer), store);

        assertThat(store.exists("blobs/" + hex)).isTrue();
    }

    @Test
    void a_feed_supplied_traversal_name_or_tag_plants_no_pointer() throws IOException {
        // The import path builds oci/<name>/tags/<reference> from feed-supplied metadata, which never passes through
        // an HTTP firewall, so a compromised source could splice a '..'-laced name or tag to plant a pointer outside
        // the oci/ namespace on a path-normalising backend. The importer applies the same name/tag guard the live push
        // and proxy paths carry: the manifest blob still lands (it is content-addressed), but no tag pointer is written.
        String type = "application/vnd.oci.image.manifest.v1+json";
        byte[] manifest = ("{\"mediaType\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        importer.importArtifact("v2/../../publish/raw/evil/manifests/1.0",
                new ByteArrayInputStream(manifest), store);
        assertThat(store.exists("blobs/" + hex)).as("the manifest blob is still imported by digest").isTrue();
        assertThat(store.list("publish")).as("no pointer is planted in the publish namespace").isEmpty();

        importer.importArtifact("v2/app/manifests/..%2f..%2fevil".replace("%2f", "/"),
                new ByteArrayInputStream(manifest), store);
        assertThat(store.readVersioned("oci/app/tags/../../evil")).as("a traversal tag is not linked").isEmpty();
    }

    @Test
    void an_oversized_manifest_is_not_buffered_or_imported() throws IOException {
        // A compromised source could label an arbitrarily large body a "manifest"; the read is capped so it is neither
        // buffered whole nor imported. 9 MiB is over the 8 MiB manifest ceiling.
        byte[] oversized = new byte[9 * 1024 * 1024];
        String hex = sha256(oversized);
        importer.importArtifact("v2/app/manifests/1.0", new ByteArrayInputStream(oversized), store);
        assertThat(store.exists("blobs/" + hex)).as("an over-ceiling manifest is skipped, not stored").isFalse();
    }
}
