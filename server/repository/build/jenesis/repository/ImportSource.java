package build.jenesis.repository;

import module java.base;

/**
 * A foreign repository to import from: it enumerates every asset of a source repository and hands each one - its
 * ecosystem format, its path within the repository, and its bytes - to a consumer. A source is the read half of a
 * migration; the {@link RepositoryImport} orchestrator is the write half, routing each asset to the
 * {@link build.jenesis.repository.format.RepositoryImporter} that handles its format. The connectors over the two
 * incumbents - {@link NexusSource} (the Nexus components REST API, paged by continuation token) and
 * {@link ArtifactorySource} (the Artifactory storage listing) - are the built-in implementations; both stream
 * through the same {@link build.jenesis.repository.format.ProxyFormat.Fetcher} the proxy uses, so an import is
 * tested without the network.
 */
public interface ImportSource {

    /** Enumerate the source's assets, handing each to {@code consumer}, and reporting a resume cursor to
     *  {@code checkpoint} after each batch is fully consumed - an opaque token to resume the walk from, or
     *  {@code null} once the walk is complete. A walk that is interrupted can be resumed from the last reported
     *  cursor (see {@link NexusSource#from}); a source with no pagination reports a single {@code null} at the end. */
    void forEach(Asset consumer, Checkpoint checkpoint) throws IOException;

    /** One asset of the source: the ecosystem {@code format}, the {@code path} within the repository, and a handle
     *  that downloads its bytes. The content is read lazily, so an asset whose format no importer handles is never
     *  downloaded - the orchestrator skips it without spending the bandwidth. */
    @FunctionalInterface
    interface Asset {
        void accept(String format, String path, Content content) throws IOException;
    }

    /** A deferred download of one asset's bytes, read only once an importer has claimed the asset's format. */
    @FunctionalInterface
    interface Content {
        byte[] read() throws IOException;
    }

    /** Notified after a batch is fully consumed with the cursor to resume from (the next page's token), or
     *  {@code null} when the walk is complete - the seam a job uses to persist progress for a resumable re-sync. */
    @FunctionalInterface
    interface Checkpoint {
        void reached(String cursor) throws IOException;
    }
}
