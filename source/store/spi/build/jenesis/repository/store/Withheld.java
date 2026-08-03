package build.jenesis.repository.store;

import module java.base;

/**
 * The blobs-namespace withhold marker - the store-layout convention ({@code withheld/<sha256>}) that gives the
 * shared {@code blobs/} namespace the retraction read-side the {@code publish/} namespace has always had through
 * {@link Publication#located}'s withheld screen. A {@code publish/}-namespace format serves through a pointer the
 * compliance gate can overlay with a {@code /quarantine} hold; a blobs-namespace format (npm, PyPI, NuGet, RubyGems,
 * Go, Debian, OCI, and the dual-layout seven) serves straight from {@code blobs/<hash>}, which no hold pointer ever
 * reached - so a retroactive KEV or license hold retracted nothing there. A marker under this convention is written
 * by the retroactive enforcement sweeps beside their {@code /quarantine} pointers and {@code holds/} records,
 * consulted by the blobs-namespace serve read (a withheld blob serves as absent) and by {@link ServableNames} for the
 * enumeration read-side, and cleared by a release - one withhold truth, two entry points.
 *
 * <p>This is the free-core home of a convention the free core already <em>reads</em> inline (OCI serving probes
 * {@code store.exists("withheld/" + hex)} on its resolved digest): naming it once in the store SPI lets
 * {@link ServableNames#withheldHash}, free OCI, and the enterprise {@code Blobs}/hold sweeps share one class rather
 * than each hand-rolling the same {@code withheld/<hash>} probe.
 *
 * <p>Both operations are idempotent (a mark of a marked hash and a clear of an unmarked one are no-ops), so the
 * sweeps' converge passes and the release/discard primitives re-run cleanly after a crash. The marker carries no
 * body worth reading - presence is the signal - and is keyed by content hash, not path: identical bytes dedupe to
 * one blob, so one marker retracts every alias at once, which is precisely the semantic a content-addressed hold
 * needs (the version's bytes are what the hold judged).
 */
public final class Withheld {

    /** The store prefix of the marker convention, {@code withheld/<lower-case sha256 hex>}. */
    public static final String ROOT = "withheld/";

    private Withheld() {
    }

    /** Mark the blob with this hash as withheld from blobs-namespace serving. Idempotent. */
    public static void mark(ArtifactStore store, String hash) throws IOException {
        if (store.readVersioned(ROOT + hash).isEmpty()) {
            store.write(ROOT + hash, new ByteArrayInputStream(new byte[0]));
        }
    }

    /** Lift the withhold marker for this hash so blobs-namespace serving resumes. Idempotent. */
    public static void clear(ArtifactStore store, String hash) throws IOException {
        if (store.readVersioned(ROOT + hash).isPresent()) {
            store.delete(ROOT + hash);
        }
    }

    /** Whether the blob with this hash is withheld - the read a blobs-namespace serve makes before streaming, so a
     *  held version answers absent (a 404) exactly as a withheld {@code publish/} pointer does. */
    public static boolean is(ArtifactStore store, String hash) throws IOException {
        return store.readVersioned(ROOT + hash).isPresent();
    }
}
