package build.jenesis.repository.importer.index;

import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSource;

/**
 * An {@link ImportSource} over a format's own published index: the walk is
 * {@link ProxyFormat#enumerate(ProxyFormat.Fetcher, URI)} - the format module's reading of its ecosystem's
 * mirror-style index - and this source only streams what the format enumerates, reporting every asset under the
 * format's own name so the orchestrator routes it to that format's importer. An enumerated path derives from a
 * foreign index (a name or tag the upstream controls, spliced into the layout path by the format), so it is
 * {@link ImportSource#safePath semi-trusted} exactly like a listing path: a traversal-laced one is skipped rather
 * than aimed at a store write outside the import's scope, the same guard every other import source applies. The
 * resume cursor is the last
 * fully-consumed layout path, reported every {@value #CHECKPOINT_INTERVAL} assets; a resumed walk re-enumerates
 * and skips to just past the cursor, and when the cursor no longer appears (the source's index changed) the walk
 * simply restarts - an import is idempotent, so re-importing is safe where losing assets is not. Bytes stream
 * lazily through the fetcher's {@code download}, so an asset the orchestrator skips is never fetched and a large
 * artifact is never buffered whole.
 */
public final class IndexSource implements ImportSource {

    private static final int CHECKPOINT_INTERVAL = 64;

    private final RepositoryFormat format;
    private final URI root;
    private final ProxyFormat.Fetcher fetcher;
    private final String cursor;

    IndexSource(RepositoryFormat format, URI root, ProxyFormat.Fetcher fetcher, String cursor) {
        this.format = format;
        this.root = root;
        this.fetcher = fetcher;
        this.cursor = cursor;
    }

    /** Whether the walk's root answers at all - any HTTP status counts, only a transport failure does not - so a
     *  submission naming an unreachable host is rejected synchronously instead of failing asynchronously. */
    boolean reachable() {
        try {
            return fetcher.fetch(root, Map.of()).isPresent();
        } catch (IOException unreachable) {
            return false;
        }
    }

    @Override
    public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
        if (cursor == null || !walk(consumer, checkpoint, cursor)) {
            walk(consumer, checkpoint, null);
        }
        checkpoint.reached(null);
    }

    /** Walks one enumeration of the index, skipping to just past {@code resume} when given; false when the cursor
     *  never appeared (nothing was consumed - the caller restarts the walk from the beginning). */
    private boolean walk(Asset consumer, Checkpoint checkpoint, String resume) throws IOException {
        try (Stream<ProxyFormat.Coordinate> coordinates = ((ProxyFormat) format).enumerate(fetcher, root)) {
            Iterator<ProxyFormat.Coordinate> iterator = coordinates.iterator();
            boolean skipping = resume != null;
            int pending = 0;
            String last = null;
            while (iterator.hasNext()) {
                ProxyFormat.Coordinate coordinate = iterator.next();
                if (!ImportSource.safePath(coordinate.path())) {
                    continue;   // a traversal-laced index path no store write should see - the enumerated path
                                // derives from a foreign index (a repository name / tag the upstream controls, which
                                // a format's enumerate splices in without promising safety), so it is only semi-
                                // trusted, exactly like the listing paths the Nexus/Artifactory/jenesis sources guard.
                }
                if (skipping) {
                    skipping = !coordinate.path().equals(resume);
                    continue;
                }
                consumer.accept(format.name(), coordinate.path(), () -> open(coordinate));
                last = coordinate.path();
                if (++pending == CHECKPOINT_INTERVAL) {
                    checkpoint.reached(last);
                    pending = 0;
                }
            }
            if (pending > 0) {
                checkpoint.reached(last);
            }
            return !skipping;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private InputStream open(ProxyFormat.Coordinate coordinate) throws IOException {
        ProxyFormat.Download download = fetcher.download(coordinate.url(), coordinate.headers())
                .orElseThrow(() -> new IOException("No response from " + coordinate.url()));
        if (download.status() != 200) {
            download.close();
            throw new IOException("Download failed (" + download.status() + ") for " + coordinate.url());
        }
        return download.body();
    }
}
