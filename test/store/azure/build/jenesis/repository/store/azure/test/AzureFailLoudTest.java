package build.jenesis.repository.store.azure.test;

import module java.base;
import module jdk.httpserver;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.azure.AzureArtifactStore;
import build.jenesis.repository.store.ArtifactStore;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fail-loud existence screen of {@link AzureArtifactStore}: {@code exists()} returns {@code false} and
 * {@code size()} returns {@code -1} <em>only</em> on a {@code 404}; every other {@link BlobStorageException} (a
 * {@code 403} auth failure, a {@code 503} throttle, a {@code 500}) must surface, or a published artifact silently
 * turns into a {@code 404} miss for as long as the backend misbehaves - and the dedup {@code exists()} probe on the
 * write path could then skip re-uploading it. The Azurite integration leg only reaches the {@code true}/{@code 404}
 * outcomes; this proves the non-404 branch with an in-process {@code jdk.httpserver} stub that answers every request
 * with a {@code 403}, driven through the real {@code azure-storage-blob} client. Needs no Docker, so it always runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AzureFailLoudTest {

    private static final String ACCOUNT = "devstoreaccount1";
    // The well-known Azurite development account key: a valid shared key so the SDK can SharedKey-sign the request the
    // stub then rejects (the stub ignores the signature - it answers 403 regardless).
    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private HttpServer server;
    private ArtifactStore store;

    @BeforeAll
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                // A non-404 backend fault (here a 403 AuthenticationFailed) that exists()/size() must not mistake for
                // an absent blob. A 403 is not retried by the SDK's default policy, so the assertion is prompt.
                exchange.getResponseHeaders().set("x-ms-error-code", "AuthenticationFailed");
                exchange.sendResponseHeaders(403, -1);
            }
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/" + ACCOUNT;
        String connectionString = "DefaultEndpointsProtocol=http;AccountName=" + ACCOUNT
                + ";AccountKey=" + KEY + ";BlobEndpoint=" + endpoint + ";";
        BlobContainerClient container = new BlobServiceClientBuilder()
                .connectionString(connectionString).buildClient().getBlobContainerClient("repo");
        store = new AzureArtifactStore(container).scope("acme");
    }

    @AfterAll
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void exists_fails_loud_on_a_non_404() {
        assertThatThrownBy(() -> store.exists("blobs/faulted"))
                .as("a non-404 makes exists() fail loud, never report the object absent")
                .isInstanceOf(BlobStorageException.class);
    }

    @Test
    public void size_fails_loud_on_a_non_404() {
        assertThatThrownBy(() -> store.size("blobs/faulted"))
                .as("a non-404 makes size() throw IOException, never return -1")
                .isInstanceOf(IOException.class);
    }
}
