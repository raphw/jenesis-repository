package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves batch archive ingestion is off unless a deployment opts in: with {@code batch-upload} at its default (off),
 * a PUT carrying {@code X-Jenesis-Explode: zip} is an inert plain upload - the archive is stored verbatim as one
 * artifact and served back byte for byte, never exploded. So the header can never trigger an unexpected fan-out on a
 * deployment that did not enable the feature.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BatchUploadDisabledTest {

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void start() {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void stop() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void the_explode_header_is_inert_when_the_feature_is_off() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("raw/inner.txt"));
            zip.write("would-explode".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray();

        HttpResponse<byte[]> put = client.send(HttpRequest.newBuilder(URI.create(base + "/raw/bundle.zip"))
                .header("X-Jenesis-Explode", "zip")
                .PUT(BodyPublishers.ofByteArray(archive)).build(), BodyHandlers.ofByteArray());
        assertThat(put.statusCode()).as("the archive is stored as one plain artifact").isEqualTo(201);

        // The archive round-trips verbatim; nothing was exploded, so the inner path does not exist.
        HttpResponse<byte[]> stored = client.send(HttpRequest.newBuilder(URI.create(base + "/raw/bundle.zip"))
                .GET().build(), BodyHandlers.ofByteArray());
        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(stored.body()).isEqualTo(archive);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/raw/inner.txt")).GET().build(),
                BodyHandlers.discarding()).statusCode()).as("no entry was exploded out").isEqualTo(404);
    }
}
