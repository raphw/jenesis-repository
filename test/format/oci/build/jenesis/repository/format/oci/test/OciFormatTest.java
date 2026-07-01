package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OCI / Docker registry format driven through {@link OciFormat#handle}: the {@code /v2/} probe advertises the API
 * version; a monolithic push stores a layer by digest and rejects a mismatched one; a chunked
 * {@code POST}/{@code PATCH}/{@code PUT} session reassembles and finalizes a layer; a manifest push records its type
 * sidecar and tag pointer and is pulled back by tag and by digest; the tag list enumerates the pushed tags; and an
 * unrecognised path is a 404.
 */
class OciFormatTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final OciFormat format = new OciFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void the_version_probe_advertises_the_distribution_api() throws IOException {
        FakeExchange slash = new FakeExchange("GET", "/v2/");
        format.handle(slash, store);
        assertThat(slash.status()).isEqualTo(200);
        assertThat(slash.responseHeader("Docker-Distribution-Api-Version")).isEqualTo("registry/2.0");

        FakeExchange bare = new FakeExchange("GET", "/v2");
        format.handle(bare, store);
        assertThat(bare.status()).isEqualTo(200);
    }

    @Test
    void a_monolithic_blob_is_pushed_by_digest_and_pulled_back() throws IOException {
        byte[] layer = "layer-bytes".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(layer);

        FakeExchange post = new FakeExchange("POST", "/v2/app/blobs/uploads/", layer,
                Map.of("digest", "sha256:" + hex), Map.of());
        format.handle(post, store);
        assertThat(post.status()).isEqualTo(201);
        assertThat(post.responseHeader("Docker-Content-Digest")).isEqualTo("sha256:" + hex);

        FakeExchange get = new FakeExchange("GET", "/v2/app/blobs/sha256:" + hex);
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(layer);
        assertThat(get.responseHeader("Docker-Content-Digest")).isEqualTo("sha256:" + hex);

        FakeExchange head = new FakeExchange("HEAD", "/v2/app/blobs/sha256:" + hex);
        format.handle(head, store);
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.responseHeader("Content-Length")).isEqualTo(String.valueOf(layer.length));

        FakeExchange miss = new FakeExchange(
                "GET", "/v2/app/blobs/sha256:" + sha256("absent".getBytes(StandardCharsets.UTF_8)));
        format.handle(miss, store);
        assertThat(miss.status()).isEqualTo(404);
    }

    @Test
    void a_digest_mismatch_on_push_is_rejected() throws IOException {
        byte[] layer = "content".getBytes(StandardCharsets.UTF_8);
        String wrong = sha256("different".getBytes(StandardCharsets.UTF_8));

        FakeExchange post = new FakeExchange("POST", "/v2/app/blobs/uploads/", layer,
                Map.of("digest", "sha256:" + wrong), Map.of());
        format.handle(post, store);
        assertThat(post.status()).isEqualTo(400);
    }

    @Test
    void a_chunked_upload_reassembles_and_finalizes_the_layer() throws IOException {
        byte[] first = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] second = "world".getBytes(StandardCharsets.UTF_8);
        byte[] full = "hello world".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(full);

        FakeExchange begin = new FakeExchange("POST", "/v2/app/blobs/uploads/");
        format.handle(begin, store);
        assertThat(begin.status()).isEqualTo(202);
        String id = begin.responseHeader("Docker-Upload-UUID");
        assertThat(id).isNotNull();

        FakeExchange patch = new FakeExchange("PATCH", "/v2/app/blobs/uploads/" + id, first);
        format.handle(patch, store);
        assertThat(patch.status()).isEqualTo(202);

        FakeExchange put = new FakeExchange("PUT", "/v2/app/blobs/uploads/" + id, second,
                Map.of("digest", "sha256:" + hex), Map.of());
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
        assertThat(put.responseHeader("Docker-Content-Digest")).isEqualTo("sha256:" + hex);

        FakeExchange get = new FakeExchange("GET", "/v2/app/blobs/sha256:" + hex);
        format.handle(get, store);
        assertThat(get.responseBytes()).isEqualTo(full);
    }

    @Test
    void a_manifest_is_pushed_and_pulled_by_tag_and_by_digest() throws IOException {
        byte[] manifest = "{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\"}"
                .getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);
        String type = "application/vnd.oci.image.manifest.v1+json";

        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/1.0", manifest,
                Map.of(), Map.of("Content-Type", type));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
        assertThat(put.responseHeader("Docker-Content-Digest")).isEqualTo("sha256:" + hex);

        FakeExchange byTag = new FakeExchange("GET", "/v2/app/manifests/1.0");
        format.handle(byTag, store);
        assertThat(byTag.status()).isEqualTo(200);
        assertThat(byTag.responseBytes()).isEqualTo(manifest);
        assertThat(byTag.responseHeader("Content-Type")).isEqualTo(type);
        assertThat(byTag.responseHeader("Docker-Content-Digest")).isEqualTo("sha256:" + hex);

        FakeExchange byDigest = new FakeExchange("GET", "/v2/app/manifests/sha256:" + hex);
        format.handle(byDigest, store);
        assertThat(byDigest.status()).isEqualTo(200);
        assertThat(byDigest.responseBytes()).isEqualTo(manifest);

        FakeExchange head = new FakeExchange("HEAD", "/v2/app/manifests/1.0");
        format.handle(head, store);
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.responseHeader("Content-Length")).isEqualTo(String.valueOf(manifest.length));

        FakeExchange missing = new FakeExchange("GET", "/v2/app/manifests/9.9");
        format.handle(missing, store);
        assertThat(missing.status()).isEqualTo(404);
    }

    @Test
    void the_tag_list_enumerates_the_pushed_tags() throws IOException {
        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/1.0",
                "{}".getBytes(StandardCharsets.UTF_8), Map.of(), Map.of());
        format.handle(put, store);

        FakeExchange tags = new FakeExchange("GET", "/v2/app/tags/list");
        format.handle(tags, store);
        assertThat(tags.status()).isEqualTo(200);
        assertThat(tags.responseHeader("Content-Type")).isEqualTo("application/json");
        assertThat(tags.responseText()).contains("\"name\":\"app\"").contains("1.0");
    }

    @Test
    void an_unrecognised_path_is_404() throws IOException {
        FakeExchange unknown = new FakeExchange("GET", "/v2/app/unknown");
        format.handle(unknown, store);
        assertThat(unknown.status()).isEqualTo(404);
    }
}
