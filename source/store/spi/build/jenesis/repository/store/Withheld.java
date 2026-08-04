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
     *  <p>Deliberately NOT gated on the marker body: a marker may carry a non-empty disposition body (an OCI/older hold
     *  writes {@code REJECT} or similar), and any present marker - whatever its body - clears. The clear is a present
     *  read-then-delete rather than a CAS on the transition edge, so under a rare concurrent double-clear both observers
     *  could fire {@code onWithholdCleared}; that is bounded and idempotent (the feed consumer re-derives from truth),
     *  and it is what P3 shipped - the exactly-once discipline the {@link #mark} CAS enforces is only required on the
     *  transition-ON leg (the actual finding). */
    public static void clear(ArtifactStore store, String hash) throws IOException {
        if (store.readVersioned(ROOT + hash).isPresent()) {
            store.delete(ROOT + hash);
            Publication.notifyWithholdCleared(ArtifactDescriptor.at(null, null).withBlob(hash, -1L), store);
        }
    }

    /** Whether the blob with this hash is withheld - the read a blobs-namespace serve makes before streaming, so a
     *  held version answers absent (a 404) exactly as a withheld {@code publish/} pointer does. */
    public static boolean is(ArtifactStore store, String hash) throws IOException {
        return store.readVersioned(ROOT + hash).isPresent();
    }
}
