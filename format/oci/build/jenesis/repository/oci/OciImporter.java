package build.jenesis.repository.oci;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;

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

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    @Override
    public boolean handles(String format) {
        return format.equals("docker") || format.equals("oci");
    }

    @Override
    public void importArtifact(String path, byte[] content, ArtifactStore store) throws IOException {
        String rest = path.startsWith("/") ? path.substring(1) : path;
        if (rest.startsWith("v2/")) {
            rest = rest.substring("v2/".length());
        }
        int manifests = rest.indexOf("/manifests/");
        if (manifests >= 0) {
            manifest(rest.substring(0, manifests), rest.substring(manifests + "/manifests/".length()), content, store);
            return;
        }
        int blobs = rest.indexOf("/blobs/");
        if (blobs >= 0) {
            String hex = sha256(content);
            if (!store.exists("blobs/" + hex)) {
                store.write("blobs/" + hex, new ByteArrayInputStream(content));
            }
        }
    }

    private void manifest(String name, String reference, byte[] content, ArtifactStore store) throws IOException {
        String hex = sha256(content);
        if (!store.exists("blobs/" + hex)) {
            store.write("blobs/" + hex, new ByteArrayInputStream(content));
        }
        store.write("oci/types/" + hex, new ByteArrayInputStream(mediaType(content).getBytes(StandardCharsets.UTF_8)));
        if (!reference.startsWith("sha256:")) {
            String key = "oci/" + name + "/tags/" + reference;
            Object token = store.readVersioned(key).map(ArtifactStore.Versioned::token).orElse(null);
            store.writeVersioned(key, ("sha256:" + hex).getBytes(StandardCharsets.UTF_8), token);
        }
    }

    private static String mediaType(byte[] manifest) {
        String text = new String(manifest, StandardCharsets.UTF_8);
        int at = text.indexOf("\"mediaType\"");
        if (at < 0) {
            return OCI_MANIFEST;
        }
        int open = text.indexOf('"', text.indexOf(':', at) + 1);
        int close = text.indexOf('"', open + 1);
        return open >= 0 && close > open ? text.substring(open + 1, close) : OCI_MANIFEST;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
