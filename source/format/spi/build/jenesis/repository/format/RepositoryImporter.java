package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The optional migration-import capability of a {@link RepositoryFormat}: a format that also implements this can
 * absorb one ecosystem's artifacts from a foreign repository manager (Nexus, Artifactory) into the content-addressed
 * store, so a deployment can migrate off an incumbent. This is the fourth {@code instanceof} capability on the one
 * discovered format seam - the shape {@link ProxyFormat} ("can pull through"), {@link ArtifactLayout} ("can describe
 * coordinates") and the enterprise blob layout already have - not a second discovered service: an importer is always
 * the migration write-half of its format, built on the same publish primitives, shipped in the same module. The
 * orchestrator discovers formats with {@link java.util.ServiceLoader} and filters them by {@code instanceof
 * RepositoryImporter}; a source connector enumerates every asset of a source repository and each is routed to the
 * format that {@link #imports} its source format, so the migration coverage is simply the set of importing formats on
 * the module path. The core ships the Maven, Docker (OCI) and raw formats with this capability; another format adds it
 * by implementing this interface, and an asset whose source format no installed format imports is skipped. An import
 * writes through the store and the format's own publish primitives, so the imported repository regenerates its own
 * indexes and metadata rather than copying the source's.
 *
 * <p>Both methods are named to avoid an erasure clash with the format seam a format already implements:
 * {@link #imports} rather than {@code handles} (which {@link RepositoryFormat#handles(String)} owns for request-path
 * claiming) and {@link #importTarget} rather than {@code describe} (which {@link ArtifactLayout#describe(String)} owns
 * for the coordinate behind a request path) - so one format object can carry the layout and the importer capability
 * at once. There is no backwards-compatibility constraint; the re-pin batch absorbs the rename.
 */
public interface RepositoryImporter {

    /** Whether this format can import a source repository of the given format - the source manager's name, e.g.
     *  {@code maven2}, {@code docker}, {@code npm}, {@code pypi}, {@code nuget}, {@code rubygems}, {@code raw}. Named
     *  {@code imports} rather than {@code handles} so it does not collide with {@link RepositoryFormat#handles(String)}
     *  (same erasure) on a format that carries both. */
    boolean imports(String sourceFormat);

    /** The <em>target-layout</em> descriptor the asset at {@code sourcePath} will occupy once imported - the coordinate
     *  the import edge screens against, so the gate assesses the real request path an accepted asset serves from
     *  ({@code /maven/<relative>}, {@code /raw/<relative>}) rather than the foreign source path. The edge screens the
     *  asset against this descriptor <em>before</em> handing the accepted body to {@link #importArtifact}; an empty
     *  result marks the asset as one this format lays out without an edge screen (OCI, whose multi-blob manifest
     *  protocol owns its own screening choke point, returns empty), and the edge streams its bytes straight to
     *  {@link #importArtifact} unchanged. Derived from the path only - no content read. Abstract on purpose: every
     *  importing format must decide the coordinate its assets land on, so a demoted layout-only import cannot silently
     *  skip the edge screen. Named {@code importTarget} rather than {@code describe} so it does not collide with
     *  {@link ArtifactLayout#describe(String)} (same erasure) on a layout-aware format that carries both. */
    Optional<ArtifactDescriptor> importTarget(String sourcePath);

    /** Lay one <em>already-screened</em> asset out - its path within the source repository and its content stream -
     *  into the content-addressed store. The content reaching here has already passed the import edge's screen (or is
     *  explicitly unscreenable, when {@link #describe} returned empty), so this only lays the bytes out in the format's
     *  namespace: it no longer screens or renders a verdict. On an edge {@code ACCEPT} the stream is the restreamed
     *  {@code blobs/<hash>} the screen stored, not the raw source download. The stream copies straight to storage; an
     *  importer that must inspect the content (to parse a manifest or a coordinate) may read it into a buffer, but a
     *  plain blob streams through unbuffered. The caller closes the stream. */
    void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException;
}
