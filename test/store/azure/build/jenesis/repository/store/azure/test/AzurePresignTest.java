package build.jenesis.repository.store.azure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.azure.AzureArtifactStore;
import build.jenesis.repository.store.ArtifactStore;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code presign} direct-fetch seam of {@link AzureArtifactStore}. Generating a service SAS is a purely local
 * signing operation over the account (shared) key - it contacts no server - so this needs no Azurite container and
 * always runs: with a shared-key client, a scoped {@code presign(key, ttl)} mints a read-only SAS URI over the
 * fully-qualified (scope-prefixed) blob.
 *
 * <p>The no-shared-key degradation the store documents (lines 62-67: {@code catch (IllegalStateException)} ->
 * {@link Optional#empty}) is deliberately not asserted here: under the pinned {@code azure-storage-blob 12.35.0},
 * {@code generateSas} on a client without a shared-key credential throws a {@link NullPointerException}
 * ("storageSharedKeyCredentials"), not an {@link IllegalStateException}, so the store's catch does not fire and
 * {@code presign} propagates rather than answering empty - asserting empty would require widening the catch in
 * production, which is out of scope for a test-only pass.
 */
class AzurePresignTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String ACCOUNT = "devstoreaccount1";
    // The well-known Azurite development account key; a valid shared key so generateSas can sign offline.
    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Test
    void presign_mints_a_read_only_sas_over_the_fully_qualified_scoped_key() {
        String connectionString = "DefaultEndpointsProtocol=https;AccountName=" + ACCOUNT
                + ";AccountKey=" + KEY + ";EndpointSuffix=core.windows.net";
        BlobContainerClient container = new BlobServiceClientBuilder()
                .connectionString(connectionString).buildClient().getBlobContainerClient("repo");
        ArtifactStore store = new AzureArtifactStore(container).scope("acme");

        URI url = store.presign("blobs/x", TTL).orElseThrow();
        assertThat(url.getPath())
                .as("the SAS URI addresses the scope-prefixed blob under the container")
                .isEqualTo("/repo/acme/blobs/x");
        assertThat(url.getQuery())
                .as("a real service SAS carries the signature, a read permission and a bounded expiry")
                .contains("sig=").contains("sp=r").contains("se=");
        assertThat(url.getHost()).isEqualTo(ACCOUNT + ".blob.core.windows.net");
    }
}
