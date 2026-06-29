package build.jenesis.repository.test;

import build.jenesis.repository.RepositoryServer;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the {@link build.jenesis.repository.raw.RawFormat} plugin over HTTP: a file is PUT, served back byte for
 * byte, found by HEAD, listed under its directory, and removed by DELETE. Proves the generic file store works as a
 * ServiceLoader-discovered format over the content-addressed store, alongside Maven and OCI.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RawFormatTest {

    @TempDir
    static Path root;

    private RepositoryServer.Running running;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void start() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        running = new RepositoryServer(store).start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port();
    }

    @AfterAll
    public void stop() {
        running.close();
    }

    @Test
    public void a_file_is_stored_served_listed_and_deleted() throws Exception {
        byte[] body = "hello, generic repository".getBytes(StandardCharsets.UTF_8);

        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/file.bin"))
                .PUT(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.discarding()).statusCode())
                .isEqualTo(201);

        HttpResponse<byte[]> get = client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/file.bin"))
                .GET().build(), BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(body);

        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/file.bin"))
                .method("HEAD", BodyPublishers.noBody()).build(), BodyHandlers.discarding()).statusCode())
                .isEqualTo(200);

        HttpResponse<String> listing = client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/"))
                .GET().build(), BodyHandlers.ofString());
        assertThat(listing.statusCode()).isEqualTo(200);
        assertThat(listing.body()).contains("file.bin");

        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/file.bin"))
                .DELETE().build(), BodyHandlers.discarding()).statusCode()).isEqualTo(204);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/raw/dir/file.bin"))
                .GET().build(), BodyHandlers.discarding()).statusCode()).isEqualTo(404);
    }
}
