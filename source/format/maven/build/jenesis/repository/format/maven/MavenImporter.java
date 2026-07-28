package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Maven repository (Nexus {@code maven2}, Artifactory {@code maven}) from an incumbent manager: each asset
 * path is a Maven coordinate, so it is published under {@code /maven/...} exactly as a deploy would - storing the blob
 * content-addressed through {@link MavenFormat#layout} and, for a jar that carries a module name, cross-publishing
 * its module view. {@code maven-metadata.xml} and its checksums are skipped: the repository generates them on read
 * from the imported version folders ({@link MavenMetadata}), so importing the source's copies would only shadow the
 * generated ones.
 */
public final class MavenImporter implements RepositoryImporter {

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("maven2") || sourceFormat.equals("maven");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        String relative = sourcePath.startsWith("/") ? sourcePath.substring(1) : sourcePath;
        // The coordinate-enriched descriptor MavenFormat parses from the /maven/ path an asset lands on, so the edge
        // screens against the real Maven coordinate/version. Empty for a generated maven-metadata.xml (which the import
        // walk then streams straight to importArtifact, where it is skipped) - the format owns that rule in one place.
        return new MavenFormat().describe("/maven/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        if (name.startsWith("maven-metadata.xml")) {
            return;
        }
        // A modular jar is cross-published into the module layout, which needs the jar's module name; layout streams
        // the content to storage and reads the module name back from there, so the importer never buffers it.
        MavenFormat.layout(store, "/maven/" + relative, content);
    }
}
