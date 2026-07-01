package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Maven repository (Nexus {@code maven2}, Artifactory {@code maven}) from an incumbent manager: each asset
 * path is a Maven coordinate, so it is published under {@code /maven/...} exactly as a deploy would - storing the blob
 * content-addressed through {@link MavenFormat#publish} and, for a jar that carries a module name, cross-publishing
 * its module view. {@code maven-metadata.xml} and its checksums are skipped: the repository generates them on read
 * from the imported version folders ({@link MavenMetadata}), so importing the source's copies would only shadow the
 * generated ones.
 */
public final class MavenImporter implements RepositoryImporter {

    @Override
    public boolean handles(String format) {
        return format.equals("maven2") || format.equals("maven");
    }

    @Override
    public void importArtifact(String path, byte[] content, ArtifactStore store) throws IOException {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        if (name.startsWith("maven-metadata.xml")) {
            return;
        }
        MavenFormat.publish(store, "/maven/" + relative, content);
    }
}
