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
    public boolean handles(String format) {
        return format.equals("raw") || format.equals("generic");
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Publication publication = new Publication(store);
        // Publish through the interceptor chain, not a raw link, so an imported asset is screened by any installed
        // compliance gate exactly as a PUT is: ACCEPT links and serves, QUARANTINE/REJECT withholds it for review.
        // Streamed, never buffered (publish hashes the body on the fly).
        publication.publish(ArtifactDescriptor.at("raw", "/raw/" + relative), content);
    }
}
