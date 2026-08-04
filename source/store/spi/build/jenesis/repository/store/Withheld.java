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

    /** How many compare-and-set attempts {@link #clear} makes before giving up. A clear races only another clearer of
     *  the very same hash (a converge re-run, a duplicate release), so contention is low and a small bound suffices -
     *  the same {@code MARK_ATTEMPTS}-shaped bound {@link DirtyIndexFeed} uses on its marker CAS. */
    private static final int CLEAR_ATTEMPTS = 8;

    /** A non-empty body a clearer stamps over a real (empty-body) marker to CLAIM the present-&gt;absent transition
     *  before it deletes: the store has an atomic-create ({@code writeVersioned(..., null)}) but no atomic-delete, so
     *  the delete transition is won by CASing this sentinel onto the marker's read token - exactly one racing clearer
     *  wins that CAS and reaches the notify. A loser reading a non-empty (claimed) marker knows the winner already owns
     *  the transition and stays silent; presence is still "withheld" through the brief claim window. */
    private static final byte[] CLEARING = "clearing".getBytes(StandardCharsets.UTF_8);

    private Withheld() {
    }

    /** Mark the blob with this hash as withheld from blobs-namespace serving. Idempotent. On an actual transition
     *  (the marker was absent) the withhold-change feed's transition-ON leg fires after the durable write, keyed by the
     *  content hash (path null - one marker retracts every alias of the bytes); the converge passes' idempotent re-marks
     *  are no-ops and raise no event.
     *
     *  <p>The transition is gated on the store's atomic-create CAS - {@code writeVersioned} with an expected-absent
     *  token - not a read-then-write: two concurrent marks of one hash both formerly observed "absent" and both fired
     *  the transition-ON leg (violating the documented exactly-once). Now exactly the observer whose conditional write
     *  lands (the store serialises the create) fires the notify; the loser's write returns {@code false} - the marker is
     *  already present - and it stays silent, the same transition-only CAS the pointer face {@link Publication#link}
     *  uses ({@code prior.isEmpty()} on a versioned write). */
    public static void mark(ArtifactStore store, String hash) throws IOException {
        // Atomic-create: expected == null requires the key be absent, so this returns true for exactly one racing
        // creator and false for an idempotent re-mark of an already-marked hash. Only the winner fires the event.
        if (store.writeVersioned(ROOT + hash, new byte[0], null)) {
            Publication.notifyWithheld(ArtifactDescriptor.at(null, null).withBlob(hash, -1L), store);
        }
    }

    /** Lift the withhold marker for this hash so blobs-namespace serving resumes. Idempotent. On an actual transition
     *  (the marker was present) the withhold-change feed's transition-OFF leg fires after the durable delete.
     *
     *  <p>The store has no atomic-delete, so the present-&gt;absent transition is won the way the store's atomic-delete
     *  idiom is expressed with the primitives it does have: CAS a {@link #CLEARING claim} sentinel onto the marker's
     *  read token, and only the observer whose CAS lands deletes the marker and fires the notify. Two concurrent clears
     *  of one hash both formerly observed "present" and both fired the transition-OFF leg; now the loser sees either an
     *  absent marker (the winner already deleted it) or a non-empty claimed one, and stays silent. Idempotent: a clear
     *  of an unmarked hash is a no-op raising no event. */
    public static void clear(ArtifactStore store, String hash) throws IOException {
        String key = ROOT + hash;
        for (int attempt = 0; attempt < CLEAR_ATTEMPTS; attempt++) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            if (current.isEmpty()) {
                return; // idempotent: nothing marked, or a racing clearer already won and removed it - no event
            }
            if (current.get().content().length != 0) {
                return; // a racing clearer already CLAIMED the transition; it fires the one transition-OFF event
            }
            // Claim the transition by CASing the sentinel onto the token just read. Winner alone reaches the delete
            // and notify; a loser's write returns false and the loop re-reads (now absent or claimed) and stays silent.
            if (store.writeVersioned(key, CLEARING, current.get().token())) {
                store.delete(key);
                Publication.notifyWithholdCleared(ArtifactDescriptor.at(null, null).withBlob(hash, -1L), store);
                return;
            }
        }
        throw new IOException("could not clear withhold marker for " + hash
                + " after " + CLEAR_ATTEMPTS + " attempts");
    }

    /** Whether the blob with this hash is withheld - the read a blobs-namespace serve makes before streaming, so a
     *  held version answers absent (a 404) exactly as a withheld {@code publish/} pointer does. */
    public static boolean is(ArtifactStore store, String hash) throws IOException {
        return store.readVersioned(ROOT + hash).isPresent();
    }
}
