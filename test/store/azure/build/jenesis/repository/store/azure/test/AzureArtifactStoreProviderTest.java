package build.jenesis.repository.store.azure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static build.jenesis.repository.store.azure.test.Requirement.requireOrSkip;

/**
 * Exercises {@link AzureArtifactStoreProvider} the way a deployment does - through {@link ArtifactStoreProvider#resolve}
 * over an injected config lookup - rather than the store class directly. It proves the provider's {@code create()}
 * wiring end to end against a real Azurite container: the {@code azure-blob} backend is discovered by name via
 * ServiceLoader, the container is auto-created, and the whole thing is driven from a supplied
 * {@code JENESIS_AZURE_CONNECTION_STRING} (the Azurite development string, pointed at the container) without touching
 * the process environment - the filesystem provider's testability, now for Azure Blob. The suite skips itself when no
 * Docker daemon is reachable.
 */
@Tag("azure")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AzureArtifactStoreProviderTest {

    private static final String IMAGE = "mcr.microsoft.com/azure-storage/azurite";
    private static final int BLOB_PORT = 10000;
    private static final String ACCOUNT = "devstoreaccount1";
    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private Docker azurite;
    private String connectionString;

    @BeforeAll
    public void start() throws Exception {
        requireOrSkip(Docker.available(), "Docker is required for the Azure Blob (Azurite) provider integration test");
        azurite = Docker.start(IMAGE, BLOB_PORT,
                "azurite-blob", "--blobHost", "0.0.0.0", "--blobPort", Integer.toString(BLOB_PORT),
                "--skipApiVersionCheck");
        int port = azurite.hostPort(BLOB_PORT);
        connectionString = "DefaultEndpointsProtocol=http;AccountName=" + ACCOUNT
                + ";AccountKey=" + KEY
                + ";BlobEndpoint=http://127.0.0.1:" + port + "/" + ACCOUNT + ";";
        awaitReady();
    }

    private void awaitReady() throws InterruptedException {
        // create() swallows a container-creation failure, so wait for Azurite to answer before driving the provider.
        BlobServiceClient service = new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
        BlobContainerClient probe = service.getBlobContainerClient("readiness");
        Instant deadline = Instant.now().plusSeconds(120);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                probe.createIfNotExists();
                return;
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("Azurite did not become ready in time", last);
    }

    @AfterAll
    public void stop() {
        if (azurite != null) {
            azurite.close();
        }
    }

    private UnaryOperator<String> config(String container) {
        Map<String, String> values = Map.of(
                "JENESIS_AZURE_CONNECTION_STRING", connectionString,
                "JENESIS_AZURE_CONTAINER", container);
        return values::get;
    }

    @Test
    public void the_azure_backend_resolves_by_name_creates_its_container_and_round_trips() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("azure-blob", config("provider-repo")).scope("acme");
        byte[] body = {4, 8, 15, 16, 23, (byte) 42};
        store.write("blobs/rt", new ByteArrayInputStream(body));
        assertThat(store.exists("blobs/rt")).isTrue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("blobs/rt", out);
        assertThat(out.toByteArray()).isEqualTo(body);
    }

    @Test
    public void a_missing_connection_string_is_a_clear_configuration_error() {
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve("azure-blob", key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JENESIS_AZURE_CONNECTION_STRING");
    }
}
