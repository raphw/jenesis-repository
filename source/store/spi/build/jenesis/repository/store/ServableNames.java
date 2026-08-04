package build.jenesis.repository.store;

import module java.base;

/**
 * The one servable-name enumeration screen: every surface that materialises published NAMES (children, versions,
 * tags, coordinates, index stanzas) routes its disclosure decision through here, choosing a {@link Policy}. It
 * answers EXACTLY what the serve path answers - it composes {@link Publication}'s withheld chain and the
 * {@link Withheld withheld/&lt;hash&gt;} marker convention, never a stricter or looser private truth - so a listing
 * and a download can never disagree on what is held. {@link Publication#located} is itself a thin wrapper over
 * {@link #state}, so serve and enumeration share the one discrimination.
 *
 * <p><b>Fail-closed by construction.</b> Every store probe this type makes is wrapped so that a name whose probe
 * throws a {@link RuntimeException} - a hostile / non-ASCII key a store backend cannot even
 * {@code resolve} ({@code FilesystemArtifactStore.resolve} does {@code root.resolve(key)} and throws
 * {@link java.nio.file.InvalidPathException} on an encoding-hostile name) - is treated as NOT disclosable and logged,
 * never rethrown. One hostile name in a page can therefore no longer 500 a whole listing, and it is never disclosed
 * either. Checked {@link IOException}s (an interceptor that fails closed on the publish path, a store I/O failure)
 * propagate exactly as they do through {@link Publication#located} today.
 *
 * <p>The {@link Policy} split is what keeps a membership surface (search, generated version indexes) from paying - or
 * being broken by - a blob stat: {@link Policy#HIDE_WITHHELD} runs only the withhold read and stats no blob, so a
 * coordinate recorded with a fake hash and no stored blob still lists, while {@link Policy#HIDE_WITHHELD_AND_GONE} is
 * bit-for-bit the serve-parity screen the browse / assets surfaces already pay for their size column.
 */
public final class ServableNames {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ServableNames.class);

    /** The reserved review subtree name under {@code publish/} - owned here once (today duplicated as an inline
     *  {@code "quarantine"} constant in the free {@code BrowseController}, {@code PublishedAssets}, {@code BrowsePanel}
     *  and the enterprise console browse). A held upload's pointer is diverted to {@code publish/quarantine<path>}. */
    public static final String QUARANTINE = "quarantine";

    /** The number of a version folder's leaves the interceptor chain is probed against in
     *  {@link #disclosableVersionFolder}: a bound so a pathologically wide folder cannot turn one folder's disclosure
     *  decision into an unbounded chain fan-out. The quarantine-pointer probe (a) is a single listing and is not
     *  capped; this caps only the chain leg (b).
     *
     *  <p>Raised well above any legitimate single-version folder: a real Maven version folder holds a handful of
     *  artifacts (main jar + pom + sources + javadoc + classifiers) each with up to five checksum/signature sidecars,
     *  a few dozen leaves at the extreme - so the exact fast path below (probe every leaf when the folder fits the cap)
     *  still covers every genuine release. Only a pathologically wide folder exceeds it, and past the cap
     *  {@link #disclosableVersionFolder} now fails CLOSED (screens the folder) rather than the former fail-OPEN, so an
     *  interceptor-only-withheld leaf beyond the probe bound can no longer leak its version name into maven-metadata. */
    private static final int PROBE_CAP = 512;

    /** The first-class discrimination {@link Publication#located} conflates into an empty {@link Optional}. */
    public enum State {
        /** Published, blob present, not withheld - a {@code GET} would serve it. */
        SERVABLE,
        /** Withheld from serving (an interceptor withholds the path, or a {@code withheld/<hash>} marker retracts the
         *  blob) - a {@code GET} answers 404 though the pointer and possibly the blob still exist. */
        WITHHELD,
        /** Published but the blob it points at is gone (a torn pointer a reconcile repairs) - not withheld. */
        BLOB_GONE,
        /** Nothing is published at the path/key. */
        UNPUBLISHED
    }

    /** What a surface hides. {@link #HIDE_WITHHELD} does ZERO blob-stat I/O (membership surfaces: search,
     *  maven-metadata versions, format version indexes - a fake-hash/no-blob member must keep listing).
     *  {@link #HIDE_WITHHELD_AND_GONE} is serve-parity (browse, {@code /assets}, raw listing) and adds the
     *  {@code blobs/<hash>} existence stat. */
    public enum Policy {
        HIDE_WITHHELD,
        HIDE_WITHHELD_AND_GONE
    }

    private final ArtifactStore store;
    private final Publication publication;

    public ServableNames(ArtifactStore store) {
        this(store, new Publication(store));
    }

    /** Reuse the caller's {@link Publication} so the withheld chain is the caller's interceptor list rather than a
     *  second, independently discovered one - the same explicit seam {@link PublishedAssets} takes. */
    public ServableNames(ArtifactStore store, Publication publication) {
        this.store = store;
        this.publication = publication;
    }

    // ---- publish/-namespace face (Maven, raw, quarantine-pointer holds) ----

    /** Full discrimination of one request path ({@code "/maven/g/a/1/a-1.jar"}). Probe order matches
     *  {@link Publication#located}: (1) interceptor chain withheld -&gt; {@link State#WITHHELD}; (2) {@code publish<path>}
     *  pointer absent -&gt; {@link State#UNPUBLISHED}; (3) {@code blobs/<hash>} stat -&gt; {@link State#SERVABLE} :
     *  {@link State#BLOB_GONE}. A probe that throws a {@link RuntimeException} (a hostile name) fails closed to
     *  {@link State#WITHHELD} - never disclosed, never thrown. */
    public State state(String requestPath) throws IOException {
        try {
            if (publication.withheld(requestPath)) {
                return State.WITHHELD;
            }
            Optional<String> hash = publication.blob(requestPath);
            if (hash.isEmpty()) {
                return State.UNPUBLISHED;
            }
            return store.exists("blobs/" + hash.get()) ? State.SERVABLE : State.BLOB_GONE;
        } catch (RuntimeException hostile) {
            LOGGER.warn("servable-name probe of {} failed; treating as withheld (fail-closed)", requestPath, hostile);
            return State.WITHHELD;
        }
    }

    /** The policy check, doing only the probes the policy needs: {@link Policy#HIDE_WITHHELD} runs ONLY the withheld
     *  chain (no pointer read, no blob stat - zero blob I/O), {@link Policy#HIDE_WITHHELD_AND_GONE} is
     *  {@code state() == SERVABLE}. Fail-closed on a hostile name. */
    public boolean disclosable(String requestPath, Policy policy) throws IOException {
        if (policy == Policy.HIDE_WITHHELD) {
            try {
                return !publication.withheld(requestPath);
            } catch (RuntimeException hostile) {
                LOGGER.warn("withheld-chain probe of {} failed; hiding (fail-closed)", requestPath, hostile);
                return false;
            }
        }
        return state(requestPath) == State.SERVABLE;
    }

    /** Version/leaf-folder disclosure for a generated version index (maven-metadata): the folder is UNDISCLOSABLE iff
     *  it is held - either (a) {@code publish/quarantine<folder>} has &ge;1 child (the free-core review-pointer
     *  convention every hold writer uses: {@code Publication.screen}'s QUARANTINE branch and the retroactive sweeps
     *  link {@code /quarantine<servedPath>} per served path), or (b) the interceptor chain withholds any of the
     *  folder's leaves, up to the {@value #PROBE_CAP}-leaf bound past which it fails CLOSED (a folder wider than the
     *  bound is screened, since its unprobed leaves cannot be proven un-held). It never stats a blob, so a fake-hash /
     *  no-blob / non-jar version keeps listing; with the free (empty) chain and no quarantine pointer a folder within
     *  the bound always lists. Fail-closed on a hostile folder name. */
    public boolean disclosableVersionFolder(String folder) throws IOException {
        try {
            // (a) The review-pointer convention: a held version has >=1 /quarantine<servedPath> pointer under it, so
            // any child under publish/quarantine<folder> means at least part of the version is held.
            if (!store.list("publish/quarantine" + folder).isEmpty()) {
                return false;
            }
            // (b) The interceptor chain withholds one of the version's leaves. Bounded, and stats no blob. A folder
            // wider than the bound fails CLOSED: it cannot be probed exhaustively without unbounding the chain
            // fan-out, and a fail-OPEN past the bound would leak the version name of an interceptor-only-withheld leaf
            // sitting beyond the probed prefix. The bound is well above any legitimate version folder, so this screens
            // only pathologically wide folders; every real release is probed in full by the exact loop below.
            List<String> leaves = store.list("publish" + folder);
            if (leaves.size() > PROBE_CAP) {
                return false;
            }
            for (String leaf : leaves) {
                if (publication.withheld(folder + "/" + leaf)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException hostile) {
            LOGGER.warn("version-folder probe of {} failed; hiding (fail-closed)", folder, hostile);
            return false;
        }
    }

    // ---- blobs-namespace face (the withheld/<hash> marker convention) ----

    /** State of a blobs-namespace pointer key ({@code "npm/<n>/tarballs/x.tgz"}, {@code "oci/<n>/tags/<t>"}): pointer
     *  absent -&gt; {@link State#UNPUBLISHED}; pointer content is a hash carrying a {@link Withheld withheld/<hash>}
     *  marker -&gt; {@link State#WITHHELD}; else {@code blobs/<hash>} stat for {@link State#SERVABLE} /
     *  {@link State#BLOB_GONE}. Exactly the decision the blobs-namespace serve read makes - shared, not cloned.
     *  Fail-closed on a hostile key. */
    public State keyState(String pointerKey) throws IOException {
        try {
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey);
            if (pointer.isEmpty()) {
                return State.UNPUBLISHED;
            }
            String hash = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
            if (Withheld.is(store, hash)) {
                return State.WITHHELD;
            }
            return store.exists("blobs/" + hash) ? State.SERVABLE : State.BLOB_GONE;
        } catch (RuntimeException hostile) {
            LOGGER.warn("blobs-namespace key probe of {} failed; treating as withheld (fail-closed)",
                    pointerKey, hostile);
            return State.WITHHELD;
        }
    }

    /** The policy check for a blobs-namespace key: {@link Policy#HIDE_WITHHELD} reads the pointer and the marker only
     *  (no blob stat - identical cost to the enterprise {@code Blobs.withheld} it replaces) and an absent pointer
     *  discloses nothing to hide (matching {@code Blobs.withheld == false}); {@link Policy#HIDE_WITHHELD_AND_GONE} is
     *  {@code keyState() == SERVABLE}. Fail-closed on a hostile key. */
    public boolean disclosableKey(String pointerKey, Policy policy) throws IOException {
        if (policy == Policy.HIDE_WITHHELD) {
            try {
                Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey);
                if (pointer.isEmpty()) {
                    return true; // no pointer -> nothing withheld to hide, exactly Blobs.withheld's false
                }
                String hash = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
                return !Withheld.is(store, hash);
            } catch (RuntimeException hostile) {
                LOGGER.warn("blobs-namespace key withhold probe of {} failed; hiding (fail-closed)",
                        pointerKey, hostile);
                return false;
            }
        }
        return keyState(pointerKey) == State.SERVABLE;
    }

    /** The raw marker probe ({@code store.exists("withheld/" + sha256)}, via {@link Withheld#is}) - the hash-level
     *  face OCI's catalog/tags screen delegates to. Fail-closed (withheld) on a hostile hash. */
    public boolean withheldHash(String sha256) throws IOException {
        try {
            return Withheld.is(store, sha256);
        } catch (RuntimeException hostile) {
            LOGGER.warn("withheld-marker probe of {} failed; treating as withheld (fail-closed)", sha256, hostile);
            return true;
        }
    }

    // ---- streaming face for paged listings ----

    /**
     * Decorate a {@code store.page}/{@code list} child consumer: forward a child name only when it is disclosable.
     * {@code prefix} is the request-path parent of the children ({@code ""} for the root, otherwise a leading-slash
     * path like {@code "/maven/g/a"}); a child the caller-supplied {@code isDirectory} probe classifies as a directory
     * is forwarded unconditionally (its own leaves carry the screen), the {@link #reviewSubtree quarantine} root child
     * is always suppressed at the root, and a leaf is forwarded only when {@link #disclosable} passes under
     * {@code policy}. A probe (or the {@code isDirectory} predicate) that throws on one name is contained - the name
     * is skipped and a WARN is logged once per listing - so one hostile name can never 500 the whole page.
     */
    public Consumer<String> screening(String prefix, Policy policy,
                                      Predicate<String> isDirectory, Consumer<String> downstream) {
        String parent = prefix == null ? "" : prefix;
        boolean[] warned = {false};
        return child -> {
            try {
                if (parent.isEmpty() && reviewSubtree(child)) {
                    return; // the review subtree is stored but never served
                }
                if (isDirectory.test(child)) {
                    downstream.accept(child); // a container forwards unconditionally; its leaves are screened
                    return;
                }
                if (disclosable(parent + "/" + child, policy)) {
                    downstream.accept(child);
                }
            } catch (RuntimeException | IOException failed) {
                if (!warned[0]) {
                    LOGGER.warn("screening a child of '{}' failed; skipping it (fail-closed)", parent, failed);
                    warned[0] = true;
                }
            }
        };
    }

    /** Whether a root child name is the reserved review subtree - the one home of the {@code "quarantine"} test that
     *  today lives inline in four free/enterprise enumeration surfaces. */
    public static boolean reviewSubtree(String rootChildName) {
        return QUARANTINE.equals(rootChildName);
    }
}
