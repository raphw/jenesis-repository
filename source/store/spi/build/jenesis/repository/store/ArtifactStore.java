package build.jenesis.repository.store;

import module java.base;

/**
 * The storage backend for the repository server: every artifact byte, generated POM, checksum and
 * metadata object the server persists or enumerates goes through this interface, so the on-disk
 * filesystem can be swapped for an object store (S3 / Azure Blob / GCS) without touching the request,
 * layout, bridge or console code. The default implementation is {@code FilesystemArtifactStore}.
 *
 * Large blobs (jars) stream through {@link #read} / {@link #write}. Small objects (POMs and
 * {@code maven-metadata.xml}) use {@link #readVersioned} / {@link #writeVersioned}: a compare-and-set
 * keyed on an opaque token, so concurrent metadata edits never lose one another. On a filesystem the
 * token is the last-modified stamp; an object-store backend maps it to the blob's ETag or generation.
 */
public interface ArtifactStore {

    /** A view confined to one tenant's subspace (a subdirectory on a filesystem, a key prefix on an object store). */
    ArtifactStore scope(String tenant);

    /**
     * Validate {@code segment} as a single traversal-free scope name and return it - defence in depth for
     * {@link #scope(String)}. Every routing edge already rejects a non-{@code [A-Za-z0-9_-]} tenant / repository name
     * before it scopes the store, so this is a backstop: it stops a store backend from silently escaping its subspace
     * on a {@code scope("../x")} or misplacing one on a {@code scope("a/b")} should a future caller forget to validate.
     * A segment carrying a path separator ({@code /} or {@code \}) or resolving to the current / parent directory
     * ({@code .}, {@code ..}, empty, or {@code null}) is rejected; a plain hidden-subspace name (the {@code .tests} /
     * {@code .scans} internal spaces) is allowed. Each backend's {@code scope} runs the argument through this.
     */
    static String segment(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Not a traversal-free scope segment: " + segment);
        }
        return segment;
    }

    /** Whether a blob exists at this object key. */
    boolean exists(String key);

    /** Stream the blob to {@code out}. */
    void read(String key, OutputStream out) throws IOException;

    /**
     * Open the blob at this key for reading, so a caller that must pull the bytes through an existing stream
     * consumer - the SHA-256 concatenation that finalizes a chunked upload, or the jar inspection that reads a
     * just-stored artifact back rather than buffering it from the network - streams it without holding it whole in
     * memory. The symmetric counterpart of {@link #write(String, InputStream)}. The key must exist; the caller
     * closes the returned stream.
     */
    InputStream open(String key) throws IOException;

    /**
     * A short-lived URL a client can fetch this key from directly (a presigned object-store GET), or empty when
     * this backend cannot mint one (the filesystem default) - the caller then streams as today. The object-store
     * backends sign a {@code GET} for the fully-qualified object (the scope's {@link #scope key prefix} plus
     * {@code key}) valid for {@code ttl}, so a serve plane can 307 the client at the bucket instead of moving the
     * bytes through the JVM; every other store (and every decorator that does not delegate) answers empty, and the
     * caller falls back to {@link #read}. A URL is a bearer capability for its lifetime, so {@code ttl} should be
     * short and the caller must have already authorized the read before minting one.
     */
    default Optional<URI> presign(String key, Duration ttl) {
        return Optional.empty();
    }

    /** Atomically store the blob from {@code in}, so a reader never observes a partial write. */
    void write(String key, InputStream in) throws IOException;

    /**
     * Store a blob content-addressed by its SHA-256, computed as {@code in} streams through, and return the hex
     * digest. The content lands at {@code blobs/<hash>} - the same content-addressed key a keyed {@link #write}
     * would use - so an identical blob already present is left untouched. This is the primitive a large artifact
     * streams through on the way from the network to storage: the store never has the hash (and so the key) before
     * it has read the bytes, and there is no move once written, so the backend digests while it writes rather than
     * buffering the whole body in memory to hash it first.
     */
    String writeBlob(InputStream in) throws IOException;

    /** The stored byte length of the blob at this key, or {@code -1} if nothing is stored there. */
    long size(String key) throws IOException;

    /** Delete the blob, tidying any now-empty container it leaves behind. */
    void delete(String key) throws IOException;

    /** The immediate child names under a key prefix (for the console browse and metadata maintenance). */
    List<String> list(String prefix);

    /**
     * Stream up to {@code limit} immediate child names under {@code prefix} to {@code consumer}, in lexicographic
     * order, starting strictly after {@code startAfter} (the empty string starts from the beginning). This is the
     * ordered-paging primitive the shared artifact walk enumerates through: repeated pages, each resuming after the
     * last name of the one before, traverse an arbitrarily large child set - the flat, millions-entry {@code blobs/}
     * namespace - without ever materialising it as one {@code List} the way {@link #list} does. The default sorts
     * {@link #list} and filters, which is correct on every backend; a backend overrides it to page natively (an
     * object store's start-after pagination, the filesystem's bounded directory scan) so a resume deep inside a
     * huge child set is a seek, not a re-list.
     */
    default void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        if (limit <= 0) {
            return;
        }
        List<String> children = new ArrayList<>(list(prefix));
        Collections.sort(children);
        int emitted = 0;
        for (String child : children) {
            if (child.compareTo(startAfter) <= 0) {
                continue;
            }
            if (emitted++ == limit) {
                break;
            }
            consumer.accept(child);
        }
    }

    /** A small object plus an opaque version token, for compare-and-set writes. */
    record Versioned(byte[] content, Object token) {
    }

    /** Read a small object with its version token; empty if absent. */
    Optional<Versioned> readVersioned(String key) throws IOException;

    /**
     * Write a small object only if the stored version still matches {@code expected} ({@code null} requires
     * the object be absent). Returns {@code false} on a mismatch, so the caller can re-read and retry; this
     * is how {@code maven-metadata.xml} stays consistent under concurrent deploys without a lock or database.
     */
    boolean writeVersioned(String key, byte[] content, Object expected) throws IOException;

    /** One compare-and-set write in a {@link #writeBatch} batch: exactly the arguments of
     *  {@link #writeVersioned(String, byte[], Object)} - store {@code content} at {@code key} only while the stored
     *  version still matches {@code expected} ({@code null} requires the key be absent). */
    record BatchWrite(String key, byte[] content, Object expected) {
    }

    /**
     * The outcome of one {@link BatchWrite}, reported by {@link #writeBatch} in input order, so a caller sees exactly
     * which keys landed and which did not:
     * <ul>
     *   <li>{@code COMMITTED} - the conditional write landed;</li>
     *   <li>{@code CONFLICTED} - the compare-and-set lost (the stored version no longer matched {@code expected}),
     *       exactly a {@code false} from {@link #writeVersioned}: the caller re-reads and retries that key;</li>
     *   <li>{@code FAILED} - the write threw, and {@link #failure()} carries the {@link IOException}.</li>
     * </ul>
     * {@code failure} is non-null only for {@code FAILED}.
     */
    record BatchOutcome(String key, Status status, IOException failure) {

        public enum Status {
            COMMITTED, CONFLICTED, FAILED
        }

        public static BatchOutcome committed(String key) {
            return new BatchOutcome(key, Status.COMMITTED, null);
        }

        public static BatchOutcome conflicted(String key) {
            return new BatchOutcome(key, Status.CONFLICTED, null);
        }

        public static BatchOutcome failed(String key, IOException failure) {
            return new BatchOutcome(key, Status.FAILED, failure);
        }
    }

    /** The bounded fan-out an object-store {@link #writeBatch} override issues its conditional writes with: a small
     *  fixed concurrency (deliberately not unbounded - a large batch must never open a connection per key), enough to
     *  turn a k-write commit from k sequential round-trips into roughly one. The filesystem backend keeps the
     *  sequential default. */
    int BATCH_FANOUT = 8;

    /**
     * Apply each {@link BatchWrite} with {@link #writeVersioned} semantics and return one {@link BatchOutcome} per
     * write, <em>in input order</em>, so a caller sees exactly which keys committed, which lost their compare-and-set
     * and which failed.
     *
     * <p><strong>Best-effort, per-key compare-and-set, explicitly NOT a transaction.</strong> There is no atomicity
     * across keys and no rollback: S3, GCS and Azure have no multi-object transaction (conditional writes are per-key
     * only), and the repository's reconcile-heals-partials model tolerates a partial batch by design. A crash or a
     * mid-batch failure leaves the keys already written committed; every key is still individually atomic and
     * compare-and-set-checked exactly as {@link #writeVersioned} - a conflicting token fails that one key
     * ({@code CONFLICTED}) or a thrown {@link IOException} fails it ({@code FAILED}) while the rest still proceed. A
     * backend may execute disjoint keys concurrently but never reorders or overlaps two writes to the same key.
     *
     * <p>The default applies the writes sequentially through {@link #writeVersioned} - correct on every backend, and
     * what the filesystem store uses; the object-store backends override it to issue the conditional writes
     * {@linkplain #BATCH_FANOUT bounded-parallel} (see {@link #writeBatchParallel}).
     */
    default List<BatchOutcome> writeBatch(List<BatchWrite> writes) throws IOException {
        List<BatchOutcome> outcomes = new ArrayList<>(writes.size());
        for (BatchWrite write : writes) {
            outcomes.add(writeOne(this, write));
        }
        return outcomes;
    }

    /**
     * Apply one {@link BatchWrite} through {@code store}'s {@link #writeVersioned} and classify the result into a
     * {@link BatchOutcome} exactly as {@link #writeBatch} documents. This is the single place every backend - the
     * default sequential loop and the object-store parallel overrides alike - turns a conditional write into an
     * outcome, so committed-vs-conflicted-vs-failed is classified identically everywhere. Never throws: a thrown
     * {@link IOException} becomes a {@code FAILED} outcome rather than escaping and aborting the rest of the batch.
     */
    static BatchOutcome writeOne(ArtifactStore store, BatchWrite write) {
        try {
            return store.writeVersioned(write.key(), write.content(), write.expected())
                    ? BatchOutcome.committed(write.key())
                    : BatchOutcome.conflicted(write.key());
        } catch (IOException failure) {
            return BatchOutcome.failed(write.key(), failure);
        }
    }

    /**
     * The shared bounded-parallel implementation the object-store {@link #writeBatch} overrides delegate to: issue
     * each write through {@link #writeOne} on a pool of at most {@link #BATCH_FANOUT} threads, collect the outcomes
     * in input order, and never reorder or overlap two writes to the same key (writes sharing a key run sequentially
     * in input order on one task; disjoint keys fan out). Best-effort, not a transaction - see {@link #writeBatch}.
     * A single write skips the pool entirely.
     */
    static List<BatchOutcome> writeBatchParallel(ArtifactStore store, List<BatchWrite> writes) throws IOException {
        int size = writes.size();
        if (size <= 1) {
            return size == 0 ? List.of() : List.of(writeOne(store, writes.get(0)));
        }
        BatchOutcome[] results = new BatchOutcome[size];
        // Group the write indices by key in input order: two writes to one key share a task and run in order (the
        // no-reorder-per-key rule above), while disjoint keys fan out across the pool - a batch of one key is never
        // parallelised into a lost update against itself.
        LinkedHashMap<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            byKey.computeIfAbsent(writes.get(index).key(), _ -> new ArrayList<>()).add(index);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(BATCH_FANOUT, byKey.size()));
        try {
            List<Future<?>> futures = new ArrayList<>(byKey.size());
            for (List<Integer> indices : byKey.values()) {
                futures.add(pool.submit(() -> {
                    for (int index : indices) {
                        results[index] = writeOne(store, writes.get(index));
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted writing batch", e);
                } catch (ExecutionException e) {
                    // writeOne captures every IOException as a FAILED outcome, so a task body cannot throw a checked
                    // failure; an escape here is an unchecked programming error - surface it, never swallow it.
                    throw new IOException("Batch write task failed", e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }
        return Arrays.asList(results);
    }

    /**
     * A {@link OutputStream} that wants only a window of a blob: a {@link #read} target a backend recognizes to
     * seek to {@link #offset()} and write {@link #length()} bytes to {@link #sink()} - a ranged {@code GET} on S3
     * or Azure, a channel seek on the filesystem - rather than reading the whole blob and discarding the rest.
     * The serving layer wraps a client {@code Range} request in one; a store that does not recognize it just
     * writes the whole blob, and the stream forwards only the window, so the result is correct either way, only
     * not seeked. A decorating store (quota, content-addressing) passes {@code out} through unchanged, so the
     * capability reaches the leaf backend.
     */
    interface RangedSink {
        long offset();

        long length();

        OutputStream sink();
    }

    /** Copy exactly {@code length} bytes from {@code in} to {@code out}. */
    static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }
}
