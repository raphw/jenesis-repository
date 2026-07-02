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
