package build.jenesis.repository.format.oci;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
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

    private static final System.Logger LOGGER = System.getLogger(OciImporter.class.getName());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        // Structural exception: an OCI push is not a single-body write - a manifest references blobs pushed as their
        // own assets - so OCI owns its screening choke point through its own manifest choreography (T26.7), not the
        // import edge. Empty tells the walk to lay each OCI asset out unscreened here; the manifest gate screens it.
        return Optional.empty();
    }

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("docker") || sourceFormat.equals("oci");
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String rest = path.startsWith("/") ? path.substring(1) : path;
        if (rest.startsWith("v2/")) {
            rest = rest.substring("v2/".length());
        }
        int manifests = rest.indexOf("/manifests/");
        if (manifests >= 0) {
            // Bound the imported manifest read exactly as the push path does (OciFormat.MAX_MANIFEST): a manifest is
            // metadata, so buffering it whole to screen it is fine only when capped - a source registry handing back a
            // multi-GB "manifest" asset must not OOM the migration.
            byte[] body = content.readNBytes(OciFormat.MAX_MANIFEST + 1);
            if (body.length > OciFormat.MAX_MANIFEST) {
                throw new IOException("imported manifest at " + rest + " exceeds the " + OciFormat.MAX_MANIFEST
                        + "-byte limit - refused.");
            }
            manifest(rest.substring(0, manifests), rest.substring(manifests + "/manifests/".length()), body, store);
            return;
        }
        if (rest.contains("/blobs/")) {
            store.writeBlob(content);
        }
    }

    private void manifest(String name, String reference, byte[] content, ArtifactStore store) throws IOException {
        // Route the import manifest through the shared OCI choke point (EPIC 26), so an import screens its manifests
        // exactly as a push and a proxy do - what OciImporter.describe() returning empty (an unscreened import edge)
        // deferred to this manifest gate. The media type is read from the manifest's own field (an import carries no
        // response headers); a withheld verdict lays out no sidecar or tag pointer, so the manifest never serves.
        try {
            OciManifests.ingest(name, reference, content, mediaType(content), store);
        } catch (OciManifests.InvalidManifest invalid) {
            // An oversized/unparseable source manifest is a refused entry: logged and skipped, storing/laying out
            // nothing (F4) - the migration continues rather than half-ingesting a manifest whose layers no later hold
            // could enumerate. The MAX_MANIFEST cap above already refuses the oversized case with an IOException; this is
            // the parse belt at the shared choke point.
            LOGGER.log(System.Logger.Level.WARNING, "skipping unparseable imported OCI manifest "
                    + name + "/manifests/" + reference + ": " + invalid.getMessage());
        }
    }

    private static String mediaType(byte[] manifest) {
        try {
            return JSON.readTree(new String(manifest, StandardCharsets.UTF_8)).path("mediaType").asString(OCI_MANIFEST);
        } catch (RuntimeException _) {
            return OCI_MANIFEST;
        }
    }
}
