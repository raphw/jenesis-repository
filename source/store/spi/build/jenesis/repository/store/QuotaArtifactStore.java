package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * An {@link ArtifactStore} that caps the total stored content bytes of the scope it wraps. Content counts against
 * the limit whether it is a finished content blob ({@code blobs/<hash>}) or the still-in-flight chunks of an OCI
 * chunked-upload session staged under {@code oci/uploads/} before they are finalized into a blob; the small pointers
 * and metadata a publish also writes are negligible and pass through unmetered. Metering the upload staging closes
 * the bypass where an authenticated writer opens chunked-upload sessions and never finalizes them: those bytes are
 * really stored, but before they land in {@code blobs/} they would otherwise grow the store without ever touching the
 * namespace the meter watched. Usage is a running counter ({@code quota/used}) kept on a {@code meter}
 * store, maintained with its compare-and-set so concurrent writers converge, and seedable from the live blobs with
 * {@link #recompute} (which a periodic reconcile corrects any drift against).
 *
 * The meter is the wrapped store itself by default, so wrapping a scope caps that scope. {@link #scope} keeps the
 * meter while descending the delegate, so wrapping a tenant root and then scoping to a repository meters every
 * repository's blobs against one tenant-wide counter and one tenant-wide limit - which is how a per-tenant quota
 * holds across a tenant's repositories even though storage is scoped per {@code <tenant>/<repository>}.
 *
 * The cap is soft at the edge: a new blob is refused only once the meter is already at or over the limit (a write
 * begun while under it completes, even if it crosses the line), so an in-flight upload is never torn in half and
 * the check stays a single counter read rather than a pre-sized one. A limit of zero or less means unlimited, in
 * which case this is a transparent pass-through.
 *
 * <p>A capped store is its own {@link ObservabilitySource}: it reports {@code jenesis.quota.used} - a bounded gauge
 * of the bytes counted against the ceiling, so the overview shows <em>data used vs available</em> and how close to
 * the cap the store is without pre-computing a percentage - and a {@code jenesis.quota.capacity} health check that
 * goes {@link Health#DEGRADED} once usage reaches the limit (a fresh blob is now refused) and {@link Health#UNKNOWN}
 * when the usage counter cannot be read. An <em>unlimited</em> store (a non-positive limit, the transparent
 * pass-through) reports nothing at all, consistent with the "a disabled plugin is not listed" rule; it runs no
 * background task of its own (a periodic reconcile drives {@link #recompute} from outside), so it reports no
 * {@code TaskStatus}.
 */
public final class QuotaArtifactStore implements ArtifactStore, ObservabilitySource {

    private static final System.Logger LOGGER = System.getLogger(QuotaArtifactStore.class.getName());

    private static final String BLOBS = "blobs/";
    // The OCI chunked-upload staging: an un-finalized session's chunks are real stored content, so they count too.
    // A deliberate, documented cross-format coupling - the same key-convention sharing the formats already lean on -
    // rather than let staged bytes bypass the cap until (if ever) they are finalized into a blob.
    private static final String UPLOADS = "oci/uploads/";
    private static final String USED = "quota/used";
    private static final int PAGE = 1000;

    private final ArtifactStore delegate;
    private final ArtifactStore meter;
    private final long limit;

    public QuotaArtifactStore(ArtifactStore delegate, long limit) {
        this(delegate, delegate, limit);
    }

    private QuotaArtifactStore(ArtifactStore delegate, ArtifactStore meter, long limit) {
        this.delegate = delegate;
        this.meter = meter;
        this.limit = limit;
    }

    /** The configured byte ceiling, or {@code 0} when unlimited. */
    public long limit() {
        return Math.max(0, limit);
    }

    /** The bytes currently counted against the limit; {@code 0} before the counter is first written or seeded. */
    public long used() throws IOException {
        Optional<Versioned> stored = meter.readVersioned(USED);
        return stored.isEmpty() ? 0L : parse(stored.get().content());
    }

    @Override
    public List<Metric> metrics() {
        long limit = limit();
        OptionalLong used = currentUsage();
        // Unlimited (a pass-through) reports nothing, like a disabled plugin; a counter that could not be read is
        // left to the health check rather than published as a misleading zero.
        if (limit <= 0 || used.isEmpty()) {
            return List.of();
        }
        return List.of(Metric.bounded("jenesis.quota.used",
                "Stored content bytes counted against the repository-wide storage quota, against the configured "
                        + "byte ceiling past which a fresh blob is refused - data used vs available, so the overview "
                        + "shows how close to the cap the store is without pre-computing a percentage.",
                used.getAsLong(), limit, "bytes"));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        long limit = limit();
        if (limit <= 0) {
            return List.of();
        }
        String description = "The repository-wide storage quota has headroom for a new blob; DEGRADED once usage "
                + "reaches the ceiling, when a fresh blob is refused until content is deleted or the cap is raised.";
        OptionalLong used = currentUsage();
        if (used.isEmpty()) {
            return List.of(HealthCheck.of("jenesis.quota.capacity", description, Health.UNKNOWN,
                    "the storage-quota usage counter could not be read"));
        }
        return List.of(used.getAsLong() >= limit
                ? HealthCheck.of("jenesis.quota.capacity", description, Health.DEGRADED,
                        "storage quota reached: a new blob is refused until content is deleted or the ceiling is raised")
                : HealthCheck.up("jenesis.quota.capacity", description));
    }

    /** The current usage, or empty when the counter could not be read - a store error the signals degrade over rather
     *  than throw through the (non-throwing) {@link ObservabilitySource} methods. */
    private OptionalLong currentUsage() {
        try {
            return OptionalLong.of(used());
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    /** Sum the live content directly under the wrapped scope and store the total as the authoritative counter: the
     *  flat {@code blobs/} namespace plus the in-flight {@code oci/uploads/} staging, each paged via {@link #page} so
     *  a millions-entry scope never materialises as one list. Summing the staging as well keeps a reseed exactly
     *  consistent with the live write/delete path, so a reconcile does not silently drop an un-finalized session's
     *  bytes and reopen the bypass the metering closes. Use this only when the meter is the scope that holds the
     *  content (the default, single-scope wrapping). */
    public long recompute() throws IOException {
        long total = 0L;
        String after = "";
        while (true) {
            List<String> names = new ArrayList<>();
            delegate.page("blobs", after, PAGE, names::add);
            for (String name : names) {
                long size = delegate.size(BLOBS + name);
                if (size > 0) {
                    total += size;
                }
            }
            if (names.size() < PAGE) {
                break;
            }
            after = names.getLast();
        }
        total += uploadBytes();
        store(total);
        return total;
    }

    /** Sum the staged chunk bytes of every un-finalized OCI upload session under {@code oci/uploads/}, paging both
     *  the session level and each session's chunk level rather than listing them. The staging is small (a handful of
     *  sessions, each itself bounded by this quota) and shallow (session then numbered chunks), so this stays cheap
     *  next to the blob pass while counting the same bytes the live path meters. */
    private long uploadBytes() throws IOException {
        long total = 0L;
        String afterSession = "";
        while (true) {
            List<String> sessions = new ArrayList<>();
            delegate.page("oci/uploads", afterSession, PAGE, sessions::add);
            for (String session : sessions) {
                String prefix = UPLOADS + session;
                String afterChunk = "";
                while (true) {
                    List<String> chunks = new ArrayList<>();
                    delegate.page(prefix, afterChunk, PAGE, chunks::add);
                    for (String chunk : chunks) {
                        long size = delegate.size(prefix + "/" + chunk);
                        if (size > 0) {
                            total += size;
                        }
                    }
                    if (chunks.size() < PAGE) {
                        break;
                    }
                    afterChunk = chunks.getLast();
                }
            }
            if (sessions.size() < PAGE) {
                break;
            }
            afterSession = sessions.getLast();
        }
        return total;
    }

    /** Overwrite the usage counter with an externally computed total (a tenant-wide reconcile sums across the
     *  tenant's repositories, where this store's own {@link #recompute} cannot reach). Retried on a compare-and-set
     *  conflict so a concurrent {@link #adjust} cannot silently drop the recomputed total; if contention persists the
     *  stale counter stands until the next reconcile corrects it, which is the counter's documented drift model. */
    public void store(long total) throws IOException {
        for (int attempt = 0; attempt < 8; attempt++) {
            Object token = meter.readVersioned(USED).map(Versioned::token).orElse(null);
            if (meter.writeVersioned(USED, Long.toString(total).getBytes(StandardCharsets.UTF_8), token)) {
                return;
            }
        }
    }

    /** Whether a key names content that consumes the quota: a finished blob, or the in-flight chunks of an OCI
     *  chunked-upload session staged under {@code oci/uploads/}. Pointers and format sidecars are not metered. */
    private static boolean metered(String key) {
        return key.startsWith(BLOBS) || key.startsWith(UPLOADS);
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        if (limit <= 0 || !metered(key)) {
            delegate.write(key, in);
            return;
        }
        boolean fresh = !delegate.exists(key);
        // A content-addressed blob whose key (blobs/<hash>) already resolves is byte-for-byte the content being
        // written: there is nothing to store, so skip the delegate write entirely rather than re-upload (and re-spool)
        // identical bytes. The dedup is probed here, before the body is ever streamed to the backend, so a re-put of
        // stored content costs one existence check and reads none of the body. Staged oci/uploads/ chunks are not
        // content-addressed, so they keep their overwrite semantics and fall through to the write below.
        if (!fresh && key.startsWith(BLOBS)) {
            return;
        }
        if (fresh && used() >= limit) {
            throw new QuotaExceededException(limit, used());
        }
        delegate.write(key, in);
        if (fresh) {
            long size = delegate.size(key);
            if (size > 0) {
                adjust(size);
            }
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        if (limit <= 0) {
            return delegate.writeBlob(in);
        }
        // The content-addressed key is only known once the stream is digested, so buffer to a temp file (on disk, not
        // in heap) while hashing, then route the stored bytes through this store's own metered write. That keyed write
        // dedups an already-stored blob (no re-upload, no second spool) and otherwise counts it exactly as a keyed
        // publish would - refusing a fresh blob at the ceiling. The ceiling check cannot be pulled ahead of the spool:
        // the hash is unknown until the body is read, so a re-deployed (byte-identical, already-stored) blob - which
        // adds no bytes and must be admitted even at a full store - is indistinguishable from a fresh one until then.
        Path temporary = Files.createTempFile("quota-blob-", null);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            try (InputStream stored = Files.newInputStream(temporary)) {
                write(BLOBS + hash, stored);
            }
            return hash;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        if (limit <= 0 || !metered(key)) {
            delegate.delete(key);
            return;
        }
        // Delete first, decrement after the delete succeeds: a failed delete leaves the blob stored, so it must stay
        // counted. Decrementing before the delete would drop the bytes from the counter while the blob lingers, and a
        // string of failed deletes would let the quota over-admit. Fail toward over-counting - the safe direction, the
        // periodic recompute reconcile corrects any drift - never toward a phantom-freed counter.
        long size = delegate.size(key);
        delegate.delete(key);
        if (size > 0) {
            adjust(-size);
        }
    }

    /** Add a signed delta to the persisted counter, retrying the compare-and-set under contention; best-effort -
     *  but a dropped delta is logged, so a counter drifting under sustained contention is visible to the operator
     *  before the periodic {@link #recompute reconcile} corrects it, not a silent surprise. */
    private void adjust(long delta) throws IOException {
        for (int attempt = 0; attempt < 8; attempt++) {
            Optional<Versioned> stored = meter.readVersioned(USED);
            long current = stored.isEmpty() ? 0L : parse(stored.get().content());
            long next = Math.max(0L, current + delta);
            Object token = stored.map(Versioned::token).orElse(null);
            if (meter.writeVersioned(USED, Long.toString(next).getBytes(StandardCharsets.UTF_8), token)) {
                return;
            }
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "quota counter update of " + delta + " bytes dropped after repeated conflicts; "
                        + "the usage counter drifts until the next recompute");
    }

    private static long parse(byte[] content) {
        try {
            return Long.parseLong(new String(content, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new QuotaArtifactStore(delegate.scope(tenant), meter, limit);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public long size(String key) throws IOException {
        return delegate.size(key);
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        delegate.read(key, out);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return delegate.open(key);
    }

    @Override
    public List<String> list(String prefix) {
        return delegate.list(prefix);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        delegate.page(prefix, startAfter, limit, consumer);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(key);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return delegate.writeVersioned(key, content, expected);
    }
}
