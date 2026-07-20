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
 * Drives the format-native {@code index} migration between two booted jenesis instances over plain HTTP - the
 * symmetry the enumeration seam buys: jenesis emits the standard OCI Distribution index ({@code /v2/_catalog},
 * {@code tags/list}) to serve native clients, so a jenesis repository is walkable by jenesis's own importer, and
 * migration off jenesis needs nothing but the format's own protocol. An image is pushed to the source instance as
 * a registry client would, a {@code POST /admin/import} naming {@code source=index, format=oci} walks the source's
 * own catalog into the target, and the target then serves the image as its own - blobs before manifest, digests
 * intact. A format that is installed but cannot enumerate (raw publishes no index) completes honestly empty, and
 * an unknown format is rejected up front.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IndexImportTest {

    private static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";

    @TempDir
    static Path root;

    private RepositoryApplication.Running source;
    private RepositoryApplication.Running target;
    private HttpClient client;
    private byte[] config;
    private byte[] layer;
    private byte[] manifest;

    @BeforeAll
    public void setUp() throws Exception {
        client = HttpClient.newHttpClient();
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - both servers stay unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        // The import SSRF screen now blocks a loopback upstream by default; the source registry is on localhost, so
        // pin the internal-host opt-out (the guard itself is proven by ImportHostGuardTest).
        System.setProperty("jenesis.repository.block-private-import-hosts", "false");
        System.setProperty("JENESIS_STORE_ROOT", root.resolve("source-store").toString());
        source = RepositoryApplication.start(0);
        System.setProperty("JENESIS_STORE_ROOT", root.resolve("target-store").toString());
        target = RepositoryApplication.start(0);

        config = "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
        layer = "layer-bytes".getBytes(StandardCharsets.UTF_8);
        push(source.port(), "/v2/demo/app/blobs/uploads/?digest=sha256:" + sha256(config), config);
        push(source.port(), "/v2/demo/app/blobs/uploads/?digest=sha256:" + sha256(layer), layer);
        manifest = ("{\"schemaVersion\":2,\"mediaType\":\"" + MANIFEST_TYPE + "\",\"config\":{\"digest\":\"sha256:"
                + sha256(config) + "\"},\"layers\":[{\"digest\":\"sha256:" + sha256(layer) + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        HttpResponse<Void> put = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + source.port() + "/v2/demo/app/manifests/v1.0"))
                .header("Content-Type", MANIFEST_TYPE).PUT(BodyPublishers.ofByteArray(manifest)).build(),
                BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(201);
    }

    @AfterAll
    public void tearDown() {
        if (target != null) {
            target.close();
        }
        if (source != null) {
            source.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty("jenesis.repository.block-private-import-hosts");
    }

    @Test
    public void a_jenesis_registry_is_migrated_through_its_own_catalog() throws Exception {
        HttpResponse<String> submitted = post("{\"source\":\"index\",\"format\":\"oci\",\"url\":\"http://localhost:"
                + source.port() + "\",\"repository\":\".\"}");
        assertThat(submitted.statusCode()).as("accepted, runs in the background").isEqualTo(202);

        String completed = pollUntilTerminal(field(submitted.body(), "job"));
        assertThat(completed).contains("\"state\":\"completed\"").contains("\"imported\":3").contains("\"skipped\":0");

        HttpResponse<String> catalog = getText("/v2/_catalog");
        assertThat(catalog.statusCode()).isEqualTo(200);
        assertThat(catalog.body()).contains("demo/app");
        assertThat(getText("/v2/demo/app/tags/list").body()).contains("\"v1.0\"");

        HttpResponse<byte[]> served = get("/v2/demo/app/manifests/v1.0");
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).as("the manifest re-serves byte-identical").isEqualTo(manifest);
        assertThat(served.headers().firstValue("Docker-Content-Digest")).contains("sha256:" + sha256(manifest));
        assertThat(get("/v2/demo/app/blobs/sha256:" + sha256(config)).body()).isEqualTo(config);
        assertThat(get("/v2/demo/app/blobs/sha256:" + sha256(layer)).body()).isEqualTo(layer);
    }

    @Test
    public void a_format_that_cannot_enumerate_completes_honestly_empty() throws Exception {
        HttpResponse<String> submitted = post("{\"source\":\"index\",\"format\":\"raw\",\"url\":\"http://localhost:"
                + source.port() + "\",\"repository\":\".\"}");
        assertThat(submitted.statusCode()).isEqualTo(202);
        String completed = pollUntilTerminal(field(submitted.body(), "job"));
        assertThat(completed).contains("\"state\":\"completed\"").contains("\"imported\":0");
    }

    @Test
    public void an_unknown_format_is_rejected_up_front() throws Exception {
        HttpResponse<String> submitted = post("{\"source\":\"index\",\"format\":\"unheard-of\","
                + "\"url\":\"http://localhost:" + source.port() + "\",\"repository\":\".\"}");
        assertThat(submitted.statusCode()).isEqualTo(400);
    }

    private void push(int port, String upload, byte[] blob) throws Exception {
        HttpResponse<Void> posted = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + upload))
                .POST(BodyPublishers.ofByteArray(blob)).build(), BodyHandlers.discarding());
        assertThat(posted.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + "/repository/admin/import"))
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + target.port() + path))
                .GET().build(), BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> getText(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + target.port() + path))
                .GET().build(), BodyHandlers.ofString());
    }

    private String pollUntilTerminal(String job) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            HttpResponse<String> status = client.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:" + target.port() + "/repository/admin/import/" + job))
                    .GET().build(), BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            if (!status.body().contains("\"state\":\"running\"")) {
                return status.body();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("job " + job + " did not finish");
    }

    private static String field(String json, String name) {
        String token = "\"" + name + "\":\"";
        int start = json.indexOf(token) + token.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
