package build.jenesis.repository.format.oci;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports a Docker / OCI registry (Nexus {@code docker}, Artifactory {@code docker}) from an incumbent manager.
 * The source presents the Distribution layout, so an asset path carrying {@code /blobs/} is a layer or config and
 * an asset path carrying {@code /manifests/} is a manifest: both are stored by their {@code sha256} digest exactly
 * as {@link OciFormat} stores a push - a layer is just {@code blobs/<hex>}, a manifest additionally records its
 * media type in the {@code oci/types/<hex>} sidecar and, when referenced by a tag rather than a digest, the
 * {@code oci/<name>/tags/<tag>} pointer. The manifest media type is read from the manifest's own {@code mediaType}
 * field, since an import carries no response headers. This is the Docker half of the core's import capability.
 */
public final class OciImporter implements RepositoryImporter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    /** An OCI manifest is a small JSON document (a few KB); cap the buffered read so a compromised import source cannot
     *  label an arbitrarily large body a "manifest" and drive an unbounded heap allocation. A body past the ceiling is
     *  not a real manifest and is skipped. */
    private static final int MAX_MANIFEST = 8 * 1024 * 1024;

    @Override
    public boolean handles(String format) {
        return format.equals("docker") || format.equals("oci");
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String rest = path.startsWith("/") ? path.substring(1) : path;
        if (rest.startsWith("v2/")) {
            rest = rest.substring("v2/".length());
        }
        int manifests = rest.indexOf("/manifests/");
        if (manifests >= 0) {
            byte[] body = content.readNBytes(MAX_MANIFEST + 1);
            if (body.length > MAX_MANIFEST) {
                return;   // an over-large "manifest" is not a real manifest - do not buffer or import it
            }
            manifest(rest.substring(0, manifests), rest.substring(manifests + "/manifests/".length()), body, store);
            return;
        }
        if (rest.contains("/blobs/")) {
            store.writeBlob(content);
        }
    }

    private void manifest(String name, String reference, byte[] content, ArtifactStore store) throws IOException {
        String hex = sha256(content);
        if (!store.exists("blobs/" + hex)) {
            store.write("blobs/" + hex, new ByteArrayInputStream(content));
        }
        store.write("oci/types/" + hex, new ByteArrayInputStream(mediaType(content).getBytes(StandardCharsets.UTF_8)));
        if (!reference.startsWith("sha256:")) {
            if (!OciFormat.isName(name) || !OciFormat.isTag(reference)) {
                // A feed-supplied name/tag that would traverse out of oci/<name>/tags/ (the live push and proxy paths
                // both apply this guard; the import path must too) is not linked - the manifest blob is still imported
                // by digest, it simply carries no tag pointer.
                return;
            }
            OciFormat.linkTag(store, "oci/" + name + "/tags/" + reference, "sha256:" + hex);
        }
    }

    private static String mediaType(byte[] manifest) {
        try {
            return JSON.readTree(new String(manifest, StandardCharsets.UTF_8)).path("mediaType").asString(OCI_MANIFEST);
        } catch (RuntimeException _) {
            return OCI_MANIFEST;
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
