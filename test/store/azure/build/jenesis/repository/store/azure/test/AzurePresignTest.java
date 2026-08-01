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
 * <p>The no-shared-key degradation the store documents ({@code catch (RuntimeException)} -> {@link Optional#empty})
 * is asserted by {@link #presign_without_a_shared_key_credential_degrades_to_empty()}: under the pinned
 * {@code azure-storage-blob 12.35.0}, {@code generateSas} on a client built from only an endpoint (no shared-key
 * credential) throws a {@link NullPointerException} ("storageSharedKeyCredentials"), not an
 * {@link IllegalStateException}. The store catches any {@link RuntimeException} from the signing attempt, so a
 * keyless client falls back to streaming (empty) rather than failing the read.
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

    @Test
    void presign_without_a_shared_key_credential_degrades_to_empty() {
        // A client built from only an endpoint carries no shared-key credential, so generateSas cannot sign a
        // service SAS. Under azure-storage-blob 12.35.0 that surfaces as a NullPointerException rather than an
        // IllegalStateException; presign must catch it and fall back to streaming (empty), not propagate.
        BlobContainerClient container = new BlobServiceClientBuilder()
                .endpoint("https://" + ACCOUNT + ".blob.core.windows.net")
                .buildClient().getBlobContainerClient("repo");
        ArtifactStore store = new AzureArtifactStore(container).scope("acme");

        assertThat(store.presign("blobs/x", TTL))
                .as("a keyless client cannot sign a SAS, so presign degrades to empty (stream instead of redirect)")
                .isEmpty();
    }
}
