package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;
import java.io.InputStream;

/**
 * Imports the artifacts of one ecosystem from a foreign repository manager (Nexus, Artifactory) into the
 * content-addressed store, so a deployment can migrate off an incumbent. Importers are discovered with
 * {@link java.util.ServiceLoader}, exactly as {@link RepositoryFormat} and the storage backends are: a source
 * connector (see the import package in the core) enumerates every asset of a source repository and the orchestrator
 * routes each one to the importer that {@link #handles} its format, so the format coverage of an import is simply
 * the set of importers on the module path. The core ships the Maven, Docker (OCI) and raw importers; other format
 * importers can be provided through this same SPI, and an asset whose format has no importer on the path is
 * skipped. An importer writes through the store and the format's own publish primitives, so the imported
 * repository regenerates its own indexes and metadata rather than copying the source's.
 */
public interface RepositoryImporter {

    /** Whether this importer handles a source repository of the given format - the source manager's name, e.g.
     *  {@code maven2}, {@code docker}, {@code npm}, {@code pypi}, {@code nuget}, {@code rubygems}, {@code raw}. */
    boolean handles(String format);

    /** Import one asset - its path within the source repository and its content stream - into the content-addressed
     *  store. The stream copies straight to storage; an importer that must inspect the content (to parse a manifest
     *  or a coordinate) may read it into a buffer, but a plain blob streams through unbuffered. The caller closes
     *  the stream. */
    void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException;
}
