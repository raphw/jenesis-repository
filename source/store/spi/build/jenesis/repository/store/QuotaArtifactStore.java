package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * An {@link ArtifactStore} that caps the total stored content bytes of the scope it wraps. Only content blobs
 * ({@code blobs/<hash>}) count against the limit - the small pointers and metadata a publish also writes are
 * negligible and pass through unmetered. Usage is a running counter ({@code quota/used}) kept on a {@code meter}
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
    private static final String USED = "quota/used";

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

    /** Sum the live blobs directly under the wrapped scope and store the total as the authoritative counter. Use
     *  this only when the meter is the scope that holds the blobs (the default, single-scope wrapping). */
    public long recompute() throws IOException {
        long total = 0L;
        for (String name : delegate.list("blobs")) {
            long size = delegate.size(BLOBS + name);
            if (size > 0) {
                total += size;
            }
        }
        store(total);
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

    @Override
    public void write(String key, InputStream in) throws IOException {
        if (limit <= 0 || !key.startsWith(BLOBS)) {
            delegate.write(key, in);
            return;
        }
        boolean fresh = !delegate.exists(key);
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
        // The content-addressed key is only known once the stream is digested, so buffer to a temp file while
        // hashing, then route the stored bytes through this store's own metered write, which enforces the quota and
        // counts the blob exactly as a keyed publish would (freshness, refusal at the limit, usage adjustment).
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
        if (limit > 0 && key.startsWith(BLOBS)) {
            long size = delegate.size(key);
            if (size > 0) {
                adjust(-size);
            }
        }
        delegate.delete(key);
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
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(key);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return delegate.writeVersioned(key, content, expected);
    }
}
