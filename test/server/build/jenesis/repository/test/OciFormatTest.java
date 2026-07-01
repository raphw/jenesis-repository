package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the {@link build.jenesis.repository.format.oci.OciFormat} plugin over HTTP exactly as a registry client does:
 * the {@code /v2/} version check, a blob upload (a session of chunks, finalized with the digest), a manifest put
 * by tag, then a pull of the manifest (by tag and by digest), the config blob, and the tag list. Proves the OCI
 * Distribution protocol round-trips through the content-addressed store, and that the format was discovered as a
 * {@code ServiceLoader} plugin alongside Maven on one server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OciFormatTest {

    private static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void start() {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port();
    }

    @AfterAll
    public void stop() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void the_v2_version_check_succeeds() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/v2/")).GET().build(), BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Docker-Distribution-Api-Version")).contains("registry/2.0");
    }

    @Test
    public void an_image_pushes_in_chunks_and_pulls_back() throws Exception {
        byte[] config = "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
        String configDigest = "sha256:" + sha256(config);

        HttpResponse<Void> session = client.send(HttpRequest.newBuilder(
                URI.create(base + "/v2/demo/app/blobs/uploads/")).POST(BodyPublishers.noBody()).build(),
                BodyHandlers.discarding());
        assertThat(session.statusCode()).isEqualTo(202);
        String location = session.headers().firstValue("Location").orElseThrow();

        HttpResponse<Void> patch = client.send(HttpRequest.newBuilder(URI.create(base + location))
                .method("PATCH", BodyPublishers.ofByteArray(config)).build(), BodyHandlers.discarding());
        assertThat(patch.statusCode()).isEqualTo(202);

        HttpResponse<Void> finalize = client.send(HttpRequest.newBuilder(
                URI.create(base + location + "?digest=" + configDigest))
                .PUT(BodyPublishers.noBody()).build(), BodyHandlers.discarding());
        assertThat(finalize.statusCode()).isEqualTo(201);
        assertThat(finalize.headers().firstValue("Docker-Content-Digest")).contains(configDigest);

        byte[] manifest = ("{\"schemaVersion\":2,\"mediaType\":\"" + MANIFEST_TYPE + "\",\"config\":{\"digest\":\""
                + configDigest + "\"},\"layers\":[]}").getBytes(StandardCharsets.UTF_8);
        String manifestDigest = "sha256:" + sha256(manifest);
        HttpResponse<Void> put = client.send(HttpRequest.newBuilder(URI.create(base + "/v2/demo/app/manifests/v1.0"))
                .header("Content-Type", MANIFEST_TYPE).PUT(BodyPublishers.ofByteArray(manifest)).build(),
                BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(201);
        assertThat(put.headers().firstValue("Docker-Content-Digest")).contains(manifestDigest);

        HttpResponse<byte[]> byTag = client.send(HttpRequest.newBuilder(
                URI.create(base + "/v2/demo/app/manifests/v1.0")).GET().build(), BodyHandlers.ofByteArray());
        assertThat(byTag.statusCode()).isEqualTo(200);
        assertThat(byTag.body()).isEqualTo(manifest);
        assertThat(byTag.headers().firstValue("Content-Type")).contains(MANIFEST_TYPE);
        assertThat(byTag.headers().firstValue("Docker-Content-Digest")).contains(manifestDigest);

        HttpResponse<byte[]> byDigest = client.send(HttpRequest.newBuilder(
                URI.create(base + "/v2/demo/app/manifests/" + manifestDigest)).GET().build(), BodyHandlers.ofByteArray());
        assertThat(byDigest.body()).isEqualTo(manifest);

        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(URI.create(base + "/v2/demo/app/blobs/"
                + configDigest)).method("HEAD", BodyPublishers.noBody()).build(), BodyHandlers.discarding());
        assertThat(head.statusCode()).isEqualTo(200);

        HttpResponse<byte[]> blob = client.send(HttpRequest.newBuilder(
                URI.create(base + "/v2/demo/app/blobs/" + configDigest)).GET().build(), BodyHandlers.ofByteArray());
        assertThat(blob.body()).isEqualTo(config);

        HttpResponse<String> tags = client.send(HttpRequest.newBuilder(
                URI.create(base + "/v2/demo/app/tags/list")).GET().build(), BodyHandlers.ofString());
        assertThat(tags.body()).contains("\"name\":\"demo/app\"").contains("\"v1.0\"");
    }

    @Test
    public void a_blob_with_a_mismatched_digest_is_rejected() throws Exception {
        HttpResponse<Void> session = client.send(HttpRequest.newBuilder(
                URI.create(base + "/v2/demo/app/blobs/uploads/")).POST(BodyPublishers.noBody()).build(),
                BodyHandlers.discarding());
        String location = session.headers().firstValue("Location").orElseThrow();
        HttpResponse<Void> finalize = client.send(HttpRequest.newBuilder(
                URI.create(base + location + "?digest=sha256:" + "00".repeat(32)))
                .PUT(BodyPublishers.ofByteArray("payload".getBytes(StandardCharsets.UTF_8))).build(),
                BodyHandlers.discarding());
        assertThat(finalize.statusCode()).isEqualTo(400);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
