package build.jenesis.repository.azure;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobDownloadContentResponse;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
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
        return new AzureArtifactStore(container, keyPrefix + tenant + "/");
    }

    @Override
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(container.getBlobClient(keyPrefix + key).exists());
        } catch (BlobStorageException e) {
            return false;
        }
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try {
            container.getBlobClient(keyPrefix + key).downloadStream(out);
        } catch (BlobStorageException e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        BlockBlobClient blob = container.getBlobClient(keyPrefix + key).getBlockBlobClient();
        try (BlobOutputStream out = blob.getBlobOutputStream(true)) {
            in.transferTo(out);
        } catch (BlobStorageException e) {
            throw new IOException("Could not write " + key, e);
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
            int status = e.getStatusCode();
            if (status == 412 || status == 409 || status == 404) {
                return false;
            }
            throw new IOException("Could not write " + key, e);
        }
    }
}
