package build.jenesis.repository.importer;

import module java.base;

/**
 * A foreign repository to import from: it enumerates every asset of a source repository and hands each one - its
 * ecosystem format, its path within the repository, and its bytes - to a consumer. A source is the read half of a
 * migration; the repository's import orchestrator is the write half, routing each asset to the
 * {@link build.jenesis.repository.format.RepositoryImporter} that handles its format. An implementation ships as its
 * own module that provides an {@link ImportSourceProvider}; the server discovers them with
 * {@link java.util.ServiceLoader}, so supporting another incumbent is a matter of adding a module, with the server
 * none the wiser. Nexus, Artifactory, the vendor-neutral Maven tree walk, the format-native index walk and jenesis
 * itself are the built-in ones.
 * Every implementation streams through the same {@link build.jenesis.repository.format.ProxyFormat.Fetcher} the proxy
 * uses, so an import is tested without the network.
 */
public interface ImportSource {

    /** Enumerate the source's assets, handing each to {@code consumer}, and reporting a resume cursor to
     *  {@code checkpoint} after each batch is fully consumed - an opaque token to resume the walk from, or
     *  {@code null} once the walk is complete. A walk that is interrupted can be resumed from the last reported
     *  cursor (a source that supports resuming takes it when created); a source with no pagination reports a single
     *  {@code null} at the end. */
    void forEach(Asset consumer, Checkpoint checkpoint) throws IOException;

    /** Whether a listing-derived path is safe to report as an asset's repository-relative path: relative, with no
     *  empty, {@code .} or {@code ..} segment and no backslash. The path a source reports becomes a store write on
     *  the import's write half, and a foreign listing is only semi-trusted (an asset's path can derive from a name
     *  someone published to the incumbent) - so a source skips an asset whose path fails this instead of letting a
     *  traversal-laced name aim the write outside the import's scope. */
    static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** One asset of the source: the ecosystem {@code format}, the {@code path} within the repository, and a handle
     *  that downloads its bytes. The content is read lazily, so an asset whose format no importer handles is never
     *  downloaded - the orchestrator skips it without spending the bandwidth. */
    @FunctionalInterface
    interface Asset {
        void accept(String format, String path, Content content) throws IOException;
    }

    /** A deferred download of one asset's bytes, opened only once an importer has claimed the asset's format. The
     *  stream copies straight from the source to storage, so a large artifact is never buffered whole; the caller
     *  owns and closes it. */
    @FunctionalInterface
    interface Content {
        InputStream open() throws IOException;
    }

    /** Notified after a batch is fully consumed with the cursor to resume from (the next page's token), or
     *  {@code null} when the walk is complete - the seam a job uses to persist progress for a resumable re-sync. */
    @FunctionalInterface
    interface Checkpoint {
        void reached(String cursor) throws IOException;
    }
}
