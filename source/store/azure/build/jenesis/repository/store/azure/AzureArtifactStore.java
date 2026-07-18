package build.jenesis.repository.store.azure;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobDownloadContentResponse;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlockBlobSimpleUploadOptions;
import com.azure.storage.blob.specialized.BlobOutputStream;
import com.azure.storage.blob.specialized.BlockBlobClient;

/**
 * An {@link ArtifactStore} backed by an Azure Blob Storage container on the official
 * {@code azure-storage-blob} SDK. A blob is the object at its name; a tenant or repository is a name
 * prefix (see {@link #scope}). The version token is the blob ETag, so {@link #writeVersioned} is a true
 * cross-node compare-and-set over Azure's long-standing optimistic concurrency: {@code expected == null}
 * uploads with {@code If-None-Match: *} (write only while the blob is still absent) and a non-null token
 * with {@code If-Match: <etag>} (write only while the blob is unchanged); a {@code 412 Precondition Failed}
 * (or the {@code 409 BlobAlreadyExists} an {@code If-None-Match: *} raises) becomes a {@code false} return,
 * so the caller re-reads and retries. Concurrent {@code maven-metadata.xml} edits and lock acquisitions
 * across many nodes therefore resolve through Azure itself, with no database or lock service.
 */
public final class AzureArtifactStore implements ArtifactStore {

    private final BlobContainerClient container;
    private final String keyPrefix;

    public AzureArtifactStore(BlobContainerClient container) {
        this(container, "");
    }

    private AzureArtifactStore(BlobContainerClient container, String keyPrefix) {
        this.container = container;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new AzureArtifactStore(container, keyPrefix + ArtifactStore.segment(tenant) + "/");
    }

    @Override
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(container.getBlobClient(keyPrefix + key).exists());
        } catch (BlobStorageException e) {
            // Only a 404 means absent; a throttle or auth failure must fail the request loudly, or a published
            // artifact silently turns into a miss (served as 404) for as long as the backend misbehaves.
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public long size(String key) throws IOException {
        try {
            return container.getBlobClient(keyPrefix + key).getProperties().getBlobSize();
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return -1L;
            }
            throw new IOException("Could not size " + key, e);
        }
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try {
            if (out instanceof ArtifactStore.RangedSink ranged) {
                try (InputStream in = container.getBlobClient(keyPrefix + key).openInputStream(
                        new BlobInputStreamOptions().setRange(new BlobRange(ranged.offset(), ranged.length())))) {
                    in.transferTo(ranged.sink());
                }
            } else {
                container.getBlobClient(keyPrefix + key).downloadStream(out);
            }
        } catch (BlobStorageException e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return container.getBlobClient(keyPrefix + key).openInputStream();
        } catch (BlobStorageException e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        BlockBlobClient blob = container.getBlobClient(keyPrefix + key).getBlockBlobClient();
        // Close only after a complete transfer: BlobOutputStream commits its staged block list in close(), even
        // when the source failed mid-stream, which would land a truncated blob at the key - breaking the SPI's
        // atomic-write contract. An abandoned (unclosed) stream commits nothing; the service expires the staged
        // blocks, so an aborted upload stores nothing at all.
        BlobOutputStream out = blob.getBlobOutputStream(true);
        try {
            in.transferTo(out);
            out.close();
        } catch (BlobStorageException e) {
            throw new IOException("Could not write " + key, e);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        // A content-addressed key is the hash of the bytes being written, so the key is unknown until the stream is
        // read; buffer the (possibly large) body to a temp file while digesting it, then upload from the file under
        // blobs/<hash> - never holding the whole artifact in memory.
        Path temporary = Files.createTempFile("azure-artifact-", null);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String key = "blobs/" + HexFormat.of().formatHex(digest.digest());
            if (!exists(key)) {
                BlockBlobClient blob = container.getBlobClient(keyPrefix + key).getBlockBlobClient();
                // Close only after a complete transfer (see write): a close after a failed transfer would commit
                // a truncated blob at blobs/<hash> - permanent corruption, since the dedupe check above would
                // then skip every future re-upload of the true content.
                try (InputStream stored = Files.newInputStream(temporary)) {
                    BlobOutputStream out = blob.getBlobOutputStream(true);
                    stored.transferTo(out);
                    out.close();
                }
            }
            return key.substring("blobs/".length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (BlobStorageException e) {
            throw new IOException("Could not write blob", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            container.getBlobClient(keyPrefix + key).deleteIfExists();
        } catch (BlobStorageException e) {
            throw new IOException("Could not delete " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        String base = keyPrefix + (prefix.isEmpty() ? "" : prefix + "/");
        TreeSet<String> names = new TreeSet<>();
        for (BlobItem item : container.listBlobsByHierarchy(base)) {
            String name = item.getName().substring(base.length());
            if (Boolean.TRUE.equals(item.isPrefix()) && name.endsWith("/")) {
                name = name.substring(0, name.length() - 1);
            }
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        if (limit <= 0) {
            return;
        }
        String base = keyPrefix + (prefix.isEmpty() ? "" : prefix + "/");
        // Azure's List Blobs offers no arbitrary start-at key (its marker is an opaque continuation token), but
        // the hierarchical listing pages lazily over contiguous key-range slices - so skip to the boundary and
        // stop at the limit; memory stays one page however large the child set, and only the skip is
        // O(position), the honest best the service gives. The SDK hands each page's blobs and prefixes as two
        // separate lists, so merge them back into key order first. That order puts a container's prefix entry
        // at `name + "/"`, AFTER a sibling whose name extends this one past a character below '/' (`app.txt`
        // the blob precedes `app/` the prefix, yet the child `app` pages first) - so every name parks and the
        // smallest parked one releases only once no smaller-named child can still arrive (held()). A released
        // name at or below startAfter is dropped rather than emitted.
        TreeSet<String> pending = new TreeSet<>();
        int emitted = 0;
        String last = null;
        for (PagedResponse<BlobItem> page : container.listBlobsByHierarchy(base).iterableByPage()) {
            List<String> ordered = new ArrayList<>();
            for (BlobItem item : page.getValue()) {
                String relative = item.getName().substring(base.length());
                if (Boolean.TRUE.equals(item.isPrefix()) && !relative.endsWith("/")) {
                    relative = relative + "/";
                }
                if (!relative.isEmpty() && !relative.equals("/")) {
                    ordered.add(relative);
                }
            }
            Collections.sort(ordered);
            for (String relative : ordered) {
                while (!pending.isEmpty() && !held(pending.first(), relative)) {
                    String name = pending.pollFirst();
                    if (name.compareTo(startAfter) > 0) {
                        consumer.accept(name);
                        last = name;
                        if (++emitted == limit) {
                            return;
                        }
                    }
                }
                String name = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
                if (!name.equals(last)) {
                    pending.add(name); // a blob and a same-named container page as one child
                }
            }
        }
        for (String name : pending) {
            if (name.compareTo(startAfter) > 0) {
                consumer.accept(name);
                if (++emitted == limit) {
                    return;
                }
            }
        }
    }

    /** Whether {@code name} may not be paged out yet at stream position {@code relative}: a proper prefix of it
     *  whose next character sorts below {@code '/'} could still arrive as a hierarchy prefix (its container key
     *  {@code prefix + "/"} sorts at or past the position), and that shorter child name must page first. */
    private static boolean held(String name, String relative) {
        for (int index = 1; index < name.length(); index++) {
            if (name.charAt(index) < '/' && relative.compareTo(name.substring(0, index) + "/") <= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        try {
            BlobDownloadContentResponse response = container.getBlobClient(keyPrefix + key)
                    .downloadContentWithResponse(null, null, null, Context.NONE);
            return Optional.of(new Versioned(response.getValue().toBytes(), response.getDeserializedHeaders().getETag()));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        BlobRequestConditions conditions = new BlobRequestConditions();
        if (expected == null) {
            conditions.setIfNoneMatch("*");
        } else {
            conditions.setIfMatch((String) expected);
        }
        BlockBlobSimpleUploadOptions options = new BlockBlobSimpleUploadOptions(BinaryData.fromBytes(content))
                .setRequestConditions(conditions);
        try {
            container.getBlobClient(keyPrefix + key).getBlockBlobClient().uploadWithResponse(options, null, Context.NONE);
            return true;
        } catch (BlobStorageException e) {
            // A container-level 404 (ContainerNotFound) is a misconfiguration or outage, not a CAS conflict: mapping
            // it to a false return would turn a missing/renamed container into silent retry-exhaustion at the caller.
            // Surface it as a real IOException. Only a blob-level 404 (the blob an If-Match refers to has been
            // deleted) is the benign conflict a re-read-and-retry resolves, alongside the 412/409 rejections.
            if (BlobErrorCode.CONTAINER_NOT_FOUND.equals(e.getErrorCode())) {
                throw new IOException("Could not write " + key + ": container does not exist", e);
            }
            int status = e.getStatusCode();
            if (status == 412 || status == 409 || status == 404) {
                return false;
            }
            throw new IOException("Could not write " + key, e);
        }
    }
}
