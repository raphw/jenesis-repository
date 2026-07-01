package build.jenesis.repository.store.azure;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;

import java.util.function.UnaryOperator;

/**
 * The {@code azure-blob} artifact-store backend over an Azure Blob Storage container. Selected with
 * {@code jenesis.repository.store=azure-blob}; configured by {@code JENESIS_AZURE_CONNECTION_STRING}
 * (a storage-account connection string, or the Azurite development string) and an optional
 * {@code JENESIS_AZURE_CONTAINER} (default {@code jenesis-repository}). The blob I/O and the conditional
 * compare-and-set semantics live in {@link AzureArtifactStore}.
 */
public final class AzureArtifactStoreProvider implements ArtifactStoreProvider {

    @Override
    public String name() {
        return "azure-blob";
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String connectionString = config.apply("JENESIS_AZURE_CONNECTION_STRING");
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalStateException(
                    "JENESIS_AZURE_CONNECTION_STRING is required for the azure-blob artifact store backend.");
        }
        String containerName = config.apply("JENESIS_AZURE_CONTAINER");
        if (containerName == null || containerName.isBlank()) {
            containerName = "jenesis-repository";
        }
        BlobServiceClient service = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
        BlobContainerClient container = service.getBlobContainerClient(containerName);
        try {
            container.createIfNotExists();
        } catch (BlobStorageException ignored) {
            // The container may already exist or the credentials may not permit creation; the operations
            // below surface a clear error if the container is truly unusable.
        }
        return new AzureArtifactStore(container);
    }
}
