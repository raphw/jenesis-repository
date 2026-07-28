package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a generic repository (Nexus {@code raw}, Artifactory {@code generic}) from an incumbent manager: a raw
 * asset has no ecosystem layout, so its path is kept verbatim under {@code /raw/...} and its bytes are stored
 * content-addressed through {@link Publication}, exactly as a {@code PUT} to {@link RawFormat} would - which also
 * dedupes a raw file that happens to match an imported jar, tarball or OCI layer to the one {@code blobs/<sha256>}.
 * This rounds out the free core's import capability alongside Maven and OCI, so the installers, archives and signed
 * binaries an organisation keeps in a raw repository migrate with the rest.
 */
public final class RawImporter implements RepositoryImporter {

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("raw") || sourceFormat.equals("generic");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        String relative = sourcePath.startsWith("/") ? sourcePath.substring(1) : sourcePath;
        // A raw asset carries no ecosystem coordinate; the target request path it lands on is its screen identity, so
        // the edge gates the /raw/ path the asset will serve from.
        return Optional.of(ArtifactDescriptor.at("raw", "/raw/" + relative));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Publication publication = new Publication(store);
        // Layout-only (EPIC 26): screening rides the ingress edge (the import walk screens each asset before handing it
        // here), so this lays the asset out - store it content-addressed (streamed, never buffered) and link its
        // /raw/ path.
        String hash = publication.storeBlob(content);
        publication.link("/raw/" + relative, hash);
    }
}
