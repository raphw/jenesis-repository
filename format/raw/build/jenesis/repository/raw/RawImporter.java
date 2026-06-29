package build.jenesis.repository.raw;

import module java.base;
import build.jenesis.repository.Publication;
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
    public boolean handles(String format) {
        return format.equals("raw") || format.equals("generic");
    }

    @Override
    public void importArtifact(String path, byte[] content, ArtifactStore store) throws IOException {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Publication publication = new Publication(store);
        publication.link("/raw/" + relative, publication.storeBlob(content));
    }
}
