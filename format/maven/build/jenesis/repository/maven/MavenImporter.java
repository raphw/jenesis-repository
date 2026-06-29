package build.jenesis.repository.maven;

import module java.base;
import build.jenesis.repository.MavenMetadata;
import build.jenesis.repository.Publication;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Maven repository (Nexus {@code maven2}, Artifactory {@code maven}) from an incumbent manager: each
 * asset path is a Maven coordinate, so it is published under {@code /maven/...} through {@link Publication} - which
 * stores the blob content-addressed and, for a jar that carries a module name, also gives it the module view and
 * the bridge binding, exactly as a deploy would. {@code maven-metadata.xml} and its checksums are skipped: the
 * repository generates them on read from the imported version folders ({@link MavenMetadata}), so importing the
 * source's copies would only shadow the generated ones. This is the Java half of the core's import capability.
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
        new Publication(store).publish("/maven/" + relative, content);
    }
}
