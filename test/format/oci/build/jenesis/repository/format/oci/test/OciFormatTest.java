package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void a_stale_un_finalized_upload_session_is_reaped_after_its_ttl() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OciFormat reaping = new OciFormat(clock, Duration.ofHours(24));

        // Open a chunked session and stream a chunk into it, but never finalize (no PUT) - the leak an authenticated
        // writer uses to grow stored bytes past the quota.
        FakeExchange begin = new FakeExchange("POST", "/v2/app/blobs/uploads/");
        reaping.handle(begin, store);
        String id = begin.responseHeader("Docker-Upload-UUID");
        FakeExchange patch = new FakeExchange("PATCH", "/v2/app/blobs/uploads/" + id,
                "half-a-layer".getBytes(StandardCharsets.UTF_8));
        reaping.handle(patch, store);
        assertThat(store.list("oci/uploads/" + id)).as("the chunk is staged").isNotEmpty();

        // Before the TTL elapses a reap spares the still-live session.
        clock.advance(Duration.ofHours(23));
        assertThat(reaping.reap(store)).isZero();
        assertThat(store.list("oci/uploads/" + id)).isNotEmpty();

        // Past the TTL the abandoned session - staged chunks and start marker both - is swept.
        clock.advance(Duration.ofHours(2));
        assertThat(reaping.reap(store)).isEqualTo(1);
        assertThat(store.list("oci/uploads/" + id)).as("the staged chunks are gone").isEmpty();
        assertThat(store.list("oci/upload-sessions")).as("the start marker is gone").doesNotContain(id);
    }

    @Test
    void a_new_upload_lazily_reaps_a_stale_session_without_a_scheduler() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OciFormat reaping = new OciFormat(clock, Duration.ofHours(24));

        FakeExchange begin = new FakeExchange("POST", "/v2/app/blobs/uploads/");
        reaping.handle(begin, store);
        String stale = begin.responseHeader("Docker-Upload-UUID");
        reaping.handle(new FakeExchange("PATCH", "/v2/app/blobs/uploads/" + stale,
                "orphan".getBytes(StandardCharsets.UTF_8)), store);

        clock.advance(Duration.ofHours(25));
        // A fresh POST sweeps the abandoned session on the upload path itself - the negative-cache lazy-sweep idiom.
        FakeExchange next = new FakeExchange("POST", "/v2/app/blobs/uploads/");
        reaping.handle(next, store);
        assertThat(next.status()).isEqualTo(202);
        assertThat(store.list("oci/uploads/" + stale)).as("the stale session was reaped on the new upload").isEmpty();
    }

    @Test
    void reaping_never_touches_a_finalized_or_reserved_namespace() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OciFormat reaping = new OciFormat(clock, Duration.ofHours(24));

        // A completed chunked push leaves no session behind, so a much-later reap has nothing to sweep and the
        // finalized blob and its catalog remain untouched.
        byte[] full = "hello world".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(full);
        FakeExchange begin = new FakeExchange("POST", "/v2/app/blobs/uploads/");
        reaping.handle(begin, store);
        String id = begin.responseHeader("Docker-Upload-UUID");
        reaping.handle(new FakeExchange("PUT", "/v2/app/blobs/uploads/" + id, full,
                Map.of("digest", "sha256:" + hex), Map.of()), store);
        assertThat(store.list("oci/upload-sessions")).as("finalizing cleared the session marker").doesNotContain(id);

        clock.advance(Duration.ofDays(30));
        assertThat(reaping.reap(store)).isZero();
        FakeExchange get = new FakeExchange("GET", "/v2/app/blobs/sha256:" + hex);
        reaping.handle(get, store);
        assertThat(get.status()).as("the finalized blob is untouched by the reaper").isEqualTo(200);
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

    private void push(String name, String reference, byte[] manifest) throws IOException {
        FakeExchange put = new FakeExchange("PUT", "/v2/" + name + "/manifests/" + reference, manifest,
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
    }

    private void pushBlob(String name, byte[] blob) throws IOException {
        FakeExchange post = new FakeExchange("POST", "/v2/" + name + "/blobs/uploads/", blob,
                Map.of("digest", "sha256:" + sha256(blob)), Map.of());
        format.handle(post, store);
        assertThat(post.status()).isEqualTo(201);
    }

    /** Mark a manifest's stored blob withheld through the same {@code withheld/<hash>} marker the blob and manifest
     *  serve paths screen on, so a pull of it 404s - the setup a held-image catalog/tags/list disclosure test asserts
     *  against. */
    private void withhold(byte[] manifest) throws IOException {
        store.write("withheld/" + sha256(manifest), new ByteArrayInputStream(new byte[0]));
    }

    @Test
    void a_fully_withheld_image_is_absent_from_the_catalog_and_its_tag_from_tags_list() throws IOException {
        byte[] kept = "{\"schemaVersion\":2,\"n\":\"kept\"}".getBytes(StandardCharsets.UTF_8);
        byte[] held = "{\"schemaVersion\":2,\"n\":\"held\"}".getBytes(StandardCharsets.UTF_8);
        push("kept", "1.0", kept);
        push("held", "1.0", held);

        // The held image's only manifest is withheld: a pull by tag already 404s (the serve path's withheld screen).
        withhold(held);
        FakeExchange pull = new FakeExchange("GET", "/v2/held/manifests/1.0");
        format.handle(pull, store);
        assertThat(pull.status()).as("a withheld manifest 404s on a pull").isEqualTo(404);

        // The catalog must not disclose the held image's name - it has no surviving tag - while the kept one remains.
        FakeExchange catalog = new FakeExchange("GET", "/v2/_catalog");
        format.handle(catalog, store);
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.responseText()).isEqualTo("{\"repositories\":[\"kept\"]}");

        // The held image's tags/list must not disclose the withheld tag (its existence included).
        FakeExchange heldTags = new FakeExchange("GET", "/v2/held/tags/list");
        format.handle(heldTags, store);
        assertThat(heldTags.status()).isEqualTo(200);
        assertThat(heldTags.responseText()).isEqualTo("{\"name\":\"held\",\"tags\":[]}");

        // The non-withheld image still lists normally.
        FakeExchange keptTags = new FakeExchange("GET", "/v2/kept/tags/list");
        format.handle(keptTags, store);
        assertThat(keptTags.responseText()).isEqualTo("{\"name\":\"kept\",\"tags\":[\"1.0\"]}");
    }

    @Test
    void a_partially_withheld_image_stays_catalogued_with_only_its_surviving_tags() throws IOException {
        byte[] shown = "{\"schemaVersion\":2,\"t\":\"shown\"}".getBytes(StandardCharsets.UTF_8);
        byte[] hidden = "{\"schemaVersion\":2,\"t\":\"hidden\"}".getBytes(StandardCharsets.UTF_8);
        push("app", "shown", shown);
        push("app", "hidden", hidden);

        // One of the image's two tags is withheld; the image keeps a surviving tag, so it stays catalogued.
        withhold(hidden);

        FakeExchange catalog = new FakeExchange("GET", "/v2/_catalog");
        format.handle(catalog, store);
        assertThat(catalog.responseText()).isEqualTo("{\"repositories\":[\"app\"]}");

        // tags/list shows the surviving tag but drops the withheld one.
        FakeExchange tags = new FakeExchange("GET", "/v2/app/tags/list");
        format.handle(tags, store);
        assertThat(tags.responseText()).isEqualTo("{\"name\":\"app\",\"tags\":[\"shown\"]}");
    }

    /** A fetcher answering from {@link OciFormat#handle} itself, so the walk consumes exactly what the format
     *  serves - the producer and the consumer of the registry index proven against each other, no server. */
    private ProxyFormat.Fetcher registry() {
        return (url, requestHeaders) -> {
            Map<String, String> query = new LinkedHashMap<>();
            if (url.getQuery() != null) {
                for (String pair : url.getQuery().split("&")) {
                    int equals = pair.indexOf('=');
                    query.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
            FakeExchange exchange = new FakeExchange("GET", url.getPath(), new byte[0], query, Map.of());
            format.handle(exchange, store);
            return Optional.of(new ProxyFormat.Fetched(
                    exchange.status(), exchange.responseBytes(), exchange.responseHeaders()));
        };
    }

    @Test
    void the_catalog_lists_images_and_pages() throws IOException {
        push("app", "1.0", "{}".getBytes(StandardCharsets.UTF_8));
        push("library/nested", "2.0", "{}".getBytes(StandardCharsets.UTF_8));

        FakeExchange all = new FakeExchange("GET", "/v2/_catalog");
        format.handle(all, store);
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.responseHeader("Content-Type")).isEqualTo("application/json");
        assertThat(all.responseText()).isEqualTo("{\"repositories\":[\"app\",\"library/nested\"]}");
        assertThat(all.responseHeader("Link")).isNull();

        FakeExchange first = new FakeExchange("GET", "/v2/_catalog", new byte[0], Map.of("n", "1"), Map.of());
        format.handle(first, store);
        assertThat(first.responseText()).isEqualTo("{\"repositories\":[\"app\"]}");
        assertThat(first.responseHeader("Link")).isEqualTo("</v2/_catalog?n=1&last=app>; rel=\"next\"");

        FakeExchange second = new FakeExchange("GET", "/v2/_catalog", new byte[0],
                Map.of("n", "1", "last", "app"), Map.of());
        format.handle(second, store);
        assertThat(second.responseText()).isEqualTo("{\"repositories\":[\"library/nested\"]}");
        assertThat(second.responseHeader("Link")).isNull();

        FakeExchange invalid = new FakeExchange("GET", "/v2/_catalog", new byte[0], Map.of("n", "abc"), Map.of());
        format.handle(invalid, store);
        assertThat(invalid.status()).isEqualTo(400);
    }

    @Test
    void enumeration_walks_the_registrys_own_index_end_to_end() throws IOException {
        byte[] config = "{\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
        byte[] layer = "layer-bytes".getBytes(StandardCharsets.UTF_8);
        pushBlob("app", config);
        pushBlob("app", layer);
        byte[] manifest = ("{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"digest\":\"sha256:" + sha256(config) + "\"},"
                + "\"layers\":[{\"digest\":\"sha256:" + sha256(layer) + "\"}]}").getBytes(StandardCharsets.UTF_8);
        push("app", "1.0", manifest);

        ProxyFormat.Fetcher registry = registry();
        List<ProxyFormat.Coordinate> coordinates =
                format.enumerate(registry, URI.create("http://registry.local")).toList();

        assertThat(coordinates).extracting(ProxyFormat.Coordinate::path).containsExactly(
                "v2/app/blobs/sha256:" + sha256(config),
                "v2/app/blobs/sha256:" + sha256(layer),
                "v2/app/manifests/1.0");
        assertThat(coordinates.getLast().headers()).containsKey("Accept");
        Map<String, byte[]> expected = new HashMap<>();
        expected.put("v2/app/blobs/sha256:" + sha256(config), config);
        expected.put("v2/app/blobs/sha256:" + sha256(layer), layer);
        expected.put("v2/app/manifests/1.0", manifest);
        for (ProxyFormat.Coordinate coordinate : coordinates) {
            Optional<ProxyFormat.Fetched> served = registry.fetch(coordinate.url(), coordinate.headers());
            assertThat(served).isPresent();
            assertThat(served.get().status()).isEqualTo(200);
            assertThat(served.get().body()).isEqualTo(expected.get(coordinate.path()));
        }
    }

    @Test
    void enumeration_expands_a_multi_arch_index_and_dedupes_shared_content() throws IOException {
        byte[] layer = "shared-layer".getBytes(StandardCharsets.UTF_8);
        pushBlob("app", layer);
        byte[] child = ("{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"layers\":[{\"digest\":\"sha256:" + sha256(layer) + "\"}]}").getBytes(StandardCharsets.UTF_8);
        push("app", "sha256:" + sha256(child), child);
        byte[] index = ("{\"mediaType\":\"application/vnd.oci.image.index.v1+json\","
                + "\"manifests\":[{\"digest\":\"sha256:" + sha256(child) + "\"}]}").getBytes(StandardCharsets.UTF_8);
        push("app", "1.0", index);
        push("app", "2.0", index);

        List<String> paths = format.enumerate(registry(), URI.create("http://registry.local"))
                .map(ProxyFormat.Coordinate::path)
                .toList();

        assertThat(paths).containsExactlyInAnyOrder(
                "v2/app/blobs/sha256:" + sha256(layer),
                "v2/app/manifests/sha256:" + sha256(child),
                "v2/app/manifests/1.0",
                "v2/app/manifests/2.0");
        assertThat(paths.indexOf("v2/app/blobs/sha256:" + sha256(layer)))
                .isLessThan(paths.indexOf("v2/app/manifests/sha256:" + sha256(child)));
        assertThat(paths.indexOf("v2/app/manifests/sha256:" + sha256(child)))
                .isLessThan(Math.min(paths.indexOf("v2/app/manifests/1.0"), paths.indexOf("v2/app/manifests/2.0")));
    }

    @Test
    void enumeration_follows_catalog_pages() throws IOException {
        Map<String, ProxyFormat.Fetched> canned = new HashMap<>();
        canned.put("http://mirror.local/v2/_catalog", new ProxyFormat.Fetched(200,
                "{\"repositories\":[\"app\"]}".getBytes(StandardCharsets.UTF_8),
                Map.of("Link", "</v2/_catalog?last=app>; rel=\"next\"")));
        canned.put("http://mirror.local/v2/_catalog?last=app", new ProxyFormat.Fetched(200,
                "{\"repositories\":[\"beta\"]}".getBytes(StandardCharsets.UTF_8), Map.of()));
        canned.put("http://mirror.local/v2/app/tags/list", new ProxyFormat.Fetched(200,
                "{\"name\":\"app\",\"tags\":[\"1.0\"]}".getBytes(StandardCharsets.UTF_8), Map.of()));
        canned.put("http://mirror.local/v2/beta/tags/list", new ProxyFormat.Fetched(200,
                "{\"name\":\"beta\",\"tags\":[]}".getBytes(StandardCharsets.UTF_8), Map.of()));
        canned.put("http://mirror.local/v2/app/manifests/1.0", new ProxyFormat.Fetched(200,
                "{\"layers\":[{\"digest\":\"sha256:abc\"}]}".getBytes(StandardCharsets.UTF_8), Map.of()));
        ProxyFormat.Fetcher fetcher = (url, headers) -> Optional.ofNullable(canned.get(url.toString()));

        List<String> paths = format.enumerate(fetcher, URI.create("http://mirror.local"))
                .map(ProxyFormat.Coordinate::path)
                .toList();

        assertThat(paths).containsExactly("v2/app/blobs/sha256:abc", "v2/app/manifests/1.0");
    }

    @Test
    void a_catalog_less_registry_fails_enumeration_up_front() {
        ProxyFormat.Fetcher fetcher = (url, headers) ->
                Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
        assertThatThrownBy(() -> format.enumerate(fetcher, URI.create("http://hub.local")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }
}
