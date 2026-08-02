package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

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
    void chunked_upload_advances_the_range_from_the_marker_without_re_scanning_staged_chunks() throws IOException {
        // Each PATCH advances the received-byte Range from the session marker, NOT by re-listing and re-summing every
        // staged chunk (which was O(N^2) store round-trips over an N-chunk push - ~N^2/2 HEADs on an object store).
        // The counting store proves no PATCH lists the chunk directory: the running total is read from the marker.
        CountingUploadsList counting = new CountingUploadsList(store);
        FakeExchange begin = new FakeExchange("POST", "/v2/app/blobs/uploads/");
        format.handle(begin, counting);
        String id = begin.responseHeader("Docker-Upload-UUID");

        byte[] chunk = "0123456789".getBytes(StandardCharsets.UTF_8);   // 10 bytes per chunk
        counting.reset();
        for (int i = 1; i <= 5; i++) {
            FakeExchange patch = new FakeExchange("PATCH", "/v2/app/blobs/uploads/" + id, chunk);
            format.handle(patch, counting);
            assertThat(patch.status()).isEqualTo(202);
            assertThat(patch.responseHeader("Range")).as("the Range reflects the running byte total")
                    .isEqualTo("0-" + (i * 10 - 1));
        }
        assertThat(counting.uploadsListings())
                .as("no PATCH re-lists the staged chunks - the running total is read from the session marker")
                .isZero();

        byte[] full = new byte[50];
        for (int i = 0; i < 5; i++) {
            System.arraycopy(chunk, 0, full, i * 10, 10);
        }
        String hex = sha256(full);
        FakeExchange put = new FakeExchange("PUT", "/v2/app/blobs/uploads/" + id, new byte[0],
                Map.of("digest", "sha256:" + hex), Map.of());
        format.handle(put, counting);
        assertThat(put.status()).as("the layer finalizes from the staged chunks").isEqualTo(201);
        FakeExchange get = new FakeExchange("GET", "/v2/app/blobs/sha256:" + hex);
        format.handle(get, counting);
        assertThat(get.responseBytes()).as("the reassembled layer is byte-for-byte the pushed chunks").isEqualTo(full);
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
    void a_manifest_push_over_the_size_cap_is_refused_and_never_stored() throws IOException {
        // The manifest PUT buffers the body whole to hand the same bytes to the compliance screen, so it is capped
        // (readNBytes(MAX_MANIFEST + 1)): a body past 4 MiB is refused 413 MANIFEST_INVALID before ingest, or an
        // authenticated pusher could PUT a multi-GB "manifest" and OOM the shared JVM (a cross-tenant DoS). This is
        // the manifest-side counterpart of the NuGet .nuspec readNBytes cap.
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/big", oversized,
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).as("a manifest past the 4 MiB cap is refused, not buffered").isEqualTo(413);
        assertThat(put.responseText()).contains("MANIFEST_INVALID").contains("exceeds");

        FakeExchange pull = new FakeExchange("GET", "/v2/app/manifests/big");
        format.handle(pull, store);
        assertThat(pull.status()).as("the refused oversized manifest left nothing behind on the registry").isEqualTo(404);
    }

    @Test
    void a_manifest_pushed_by_a_digest_its_body_does_not_hash_to_is_refused() throws IOException {
        // A push BY DIGEST must actually hash to that digest - the manifest-side content-address check. Without it a
        // client could PUT /manifests/sha256:<X> with a body hashing to Y and the registry would accept it and answer
        // Docker-Content-Digest: sha256:Y, silently disagreeing with the digest the client and any content-addressed
        // puller trusted.
        byte[] manifest = "{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\"}"
                .getBytes(StandardCharsets.UTF_8);
        String wrong = sha256("a different manifest".getBytes(StandardCharsets.UTF_8));
        assertThat(wrong).isNotEqualTo(sha256(manifest));

        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/sha256:" + wrong, manifest,
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).as("a manifest that does not hash to its referenced digest is refused").isEqualTo(400);
        assertThat(put.responseText()).contains("MANIFEST_INVALID").contains("does not hash");
    }

    @Test
    void a_manifest_push_to_a_reference_that_is_neither_a_digest_nor_a_valid_tag_is_refused() throws IOException {
        // A reference that is neither a digest nor a well-formed tag would land as an oci/<name>/tags/<ref> store key,
        // so a traversal- or over-long reference could aim the tag-pointer write at a neighbouring key space; it is
        // refused 400 before anything is stored. An over-128-char reference is not a valid tag (isTag caps length).
        String overlong = "x".repeat(129);
        FakeExchange put = new FakeExchange("PUT", "/v2/app/manifests/" + overlong, "{}".getBytes(StandardCharsets.UTF_8),
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(put, store);
        assertThat(put.status()).as("a reference that is neither a digest nor a valid tag is refused").isEqualTo(400);

        FakeExchange pull = new FakeExchange("GET", "/v2/app/manifests/" + overlong);
        format.handle(pull, store);
        assertThat(pull.status()).as("the refused reference created no tag pointer").isEqualTo(404);
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

    @Test
    void the_catalog_walk_descends_nested_image_names_iteratively() throws IOException {
        // The _catalog walk descends the oci/ pointer tree, whose depth is a client-controlled multi-segment image
        // name; it is walked with an explicit work-list, never recursion, so a deeply nested push cannot overflow the
        // call stack on a plain GET /v2/_catalog (a StackOverflowError that would slip past the IOException/
        // RuntimeException handlers and deny the catalog to every reader). Images at several nesting depths must all
        // appear - proof the work-list visits every level and drops none.
        push("top", "1.0", "{}".getBytes(StandardCharsets.UTF_8));
        push("group/mid", "1.0", "{}".getBytes(StandardCharsets.UTF_8));
        String deep = String.join("/", Collections.nCopies(40, "a"));
        push(deep, "1.0", "{}".getBytes(StandardCharsets.UTF_8));

        FakeExchange catalog = new FakeExchange("GET", "/v2/_catalog");
        format.handle(catalog, store);
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.responseText()).contains("\"top\"").contains("group/mid").contains(deep);
    }

    @Test
    void a_traversal_laced_image_name_names_no_manifest_tag_list_or_upload() throws IOException {
        // A '..'- or empty-segment-laced image name is refused in-format before it becomes an oci/<name>/... store key
        // - the name-side counterpart of the digest and tag guards, so the one multi-segment request element does not
        // lean on the servlet firewall alone.
        FakeExchange manifest = new FakeExchange("GET", "/v2/a/../evil/manifests/1.0");
        format.handle(manifest, store);
        assertThat(manifest.status()).as("a traversal-laced name names no manifest").isEqualTo(404);

        FakeExchange tags = new FakeExchange("GET", "/v2/a/../evil/tags/list");
        format.handle(tags, store);
        assertThat(tags.status()).as("a traversal-laced name lists no tags").isEqualTo(404);

        FakeExchange upload = new FakeExchange("POST", "/v2/a/../evil/blobs/uploads/");
        format.handle(upload, store);
        assertThat(upload.status()).as("a traversal-laced name opens no upload session").isEqualTo(404);
    }

    @Test
    void a_chunk_upload_to_a_traversal_laced_session_id_is_refused_before_any_store_write() throws IOException {
        // The upload session id is echoed back by the client on PATCH/PUT and flows into oci/uploads/<id> chunk keys; a
        // '..'-laced id must not aim those staged-chunk writes and deletes at a neighbouring key space. Guarded like the
        // image name, it 404s before any store write - the id-side counterpart of the name guard, so the one client-
        // echoed segment does not lean on the servlet firewall alone.
        FakeExchange patch = new FakeExchange("PATCH", "/v2/app/blobs/uploads/../../evil",
                "chunk".getBytes(StandardCharsets.UTF_8));
        format.handle(patch, store);
        assertThat(patch.status()).as("a traversal-laced session id stages no chunk").isEqualTo(404);
        assertThat(store.list("oci/uploads")).as("no staged chunk was written under a traversal id").isEmpty();
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

        // A non-positive n is refused the same way (not a 500): with images present, n=0 would empty the page list then
        // read getLast() off it, and n=-1 would index a subList with a negative fromIndex - each an unhandled crash.
        for (String bad : List.of("0", "-1")) {
            FakeExchange nonPositive = new FakeExchange("GET", "/v2/_catalog", new byte[0], Map.of("n", bad), Map.of());
            format.handle(nonPositive, store);
            assertThat(nonPositive.status()).as("n=" + bad + " is a 400, not a 500").isEqualTo(400);
        }
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
    void enumeration_refuses_a_cross_origin_page_link_to_a_private_host() {
        // The upstream catalog page names its own next page at a loopback/metadata host - an SSRF steer. The first page
        // is same-origin with the operator-configured root and is read, but the cross-origin private next-link must be
        // refused before it is fetched, exactly as the redirect chain and the index importer screen an
        // upstream-controlled URL. Without the guard the walk would GET http://127.0.0.1/... on the operator's behalf.
        Map<String, ProxyFormat.Fetched> canned = new HashMap<>();
        canned.put("http://mirror.local/v2/_catalog", new ProxyFormat.Fetched(200,
                "{\"repositories\":[\"app\"]}".getBytes(StandardCharsets.UTF_8),
                Map.of("Link", "<http://127.0.0.1/v2/_catalog?last=app>; rel=\"next\"")));
        canned.put("http://mirror.local/v2/app/tags/list", new ProxyFormat.Fetched(200,
                "{\"name\":\"app\",\"tags\":[]}".getBytes(StandardCharsets.UTF_8), Map.of()));
        ProxyFormat.Fetcher fetcher = (url, headers) -> Optional.ofNullable(canned.get(url.toString()));

        assertThatThrownBy(() -> format.enumerate(fetcher, URI.create("http://mirror.local"))
                .map(ProxyFormat.Coordinate::path).toList())
                .hasMessageContaining("private/loopback")
                .hasRootCauseInstanceOf(IOException.class);
    }

    @Test
    void a_catalog_less_registry_fails_enumeration_up_front() {
        ProxyFormat.Fetcher fetcher = (url, headers) ->
                Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
        assertThatThrownBy(() -> format.enumerate(fetcher, URI.create("http://hub.local")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }

    private static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";

    private static final URI UPSTREAM = URI.create("http://upstream.local");

    /** A fetcher that serves one canned upstream response, counting how many times upstream was hit - so a test can
     *  prove a refused (uncached) pull re-hits upstream on the next attempt while a cached one does not. */
    private static ProxyFormat.Fetcher counting(String path, ProxyFormat.Fetched response, AtomicInteger hits) {
        String target = UPSTREAM + path;
        return (url, headers) -> {
            if (!url.toString().equals(target)) {
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(response);
        };
    }

    @Test
    void a_proxied_blob_matching_its_digest_is_cached_and_served() throws IOException {
        byte[] blob = "the-real-layer".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(blob);
        AtomicInteger hits = new AtomicInteger();
        ProxyFormat.Fetcher fetcher = counting("/v2/app/blobs/" + digest,
                new ProxyFormat.Fetched(200, blob, Map.of("Docker-Content-Digest", digest)), hits);

        FakeExchange pull = new FakeExchange("GET", "/v2/app/blobs/" + digest);
        boolean served = format.proxy(pull, store, UPSTREAM, fetcher);

        assertThat(served).as("the proxy served the blob").isTrue();
        assertThat(pull.status()).isEqualTo(200);
        assertThat(pull.responseBytes()).as("the served bytes are the fetched blob").isEqualTo(blob);
        assertThat(hits.get()).as("the proxy fetched upstream once").isEqualTo(1);
        assertThat(store.exists("blobs/" + sha256(blob))).as("the blob is cached under its digest").isTrue();

        // The cached blob now serves from the local store - the dispatcher's local-hit path (handle), which never
        // reaches the proxy leg, so upstream is not touched again.
        FakeExchange local = new FakeExchange("GET", "/v2/app/blobs/" + digest);
        format.handle(local, store);
        assertThat(local.status()).isEqualTo(200);
        assertThat(local.responseBytes()).as("the cached blob re-serves locally").isEqualTo(blob);
        assertThat(hits.get()).as("a local hit does not touch upstream").isEqualTo(1);
    }

    @Test
    void a_proxied_blob_mismatching_its_digest_is_refused_and_not_cached() throws IOException {
        byte[] tampered = "tampered-in-transit".getBytes(StandardCharsets.UTF_8);
        byte[] legit = "what-the-digest-names".getBytes(StandardCharsets.UTF_8);
        String requested = "sha256:" + sha256(legit); // the client asks for legit; upstream returns tampered bytes
        AtomicInteger hits = new AtomicInteger();
        ProxyFormat.Fetcher fetcher = counting("/v2/app/blobs/" + requested,
                new ProxyFormat.Fetched(200, tampered, Map.of()), hits);

        FakeExchange pull = new FakeExchange("GET", "/v2/app/blobs/" + requested);
        boolean served = format.proxy(pull, store, UPSTREAM, fetcher);

        assertThat(served).as("a blob whose bytes belie the requested digest is refused").isFalse();
        assertThat(store.exists("blobs/" + sha256(legit)))
                .as("nothing is cached under the requested digest").isFalse();

        // A re-pull re-hits upstream, proving the mismatched bytes were never linked under the requested digest.
        format.proxy(new FakeExchange("GET", "/v2/app/blobs/" + requested), store, UPSTREAM, fetcher);
        assertThat(hits.get()).as("a re-pull re-hits upstream - nothing was cached").isEqualTo(2);
    }

    @Test
    void a_proxied_manifest_by_tag_matching_the_content_digest_is_cached_and_served() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + MANIFEST_TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(manifest);
        AtomicInteger hits = new AtomicInteger();
        ProxyFormat.Fetcher fetcher = counting("/v2/app/manifests/1.0",
                new ProxyFormat.Fetched(200, manifest,
                        Map.of("Content-Type", MANIFEST_TYPE, "Docker-Content-Digest", digest)), hits);

        FakeExchange pull = new FakeExchange("GET", "/v2/app/manifests/1.0",
                new byte[0], Map.of(), Map.of("Accept", MANIFEST_TYPE));
        boolean served = format.proxy(pull, store, UPSTREAM, fetcher);

        assertThat(served).as("the proxy served the manifest").isTrue();
        assertThat(pull.status()).isEqualTo(200);
        assertThat(pull.responseBytes()).isEqualTo(manifest);
        assertThat(store.exists("blobs/" + sha256(manifest))).as("the manifest is cached by digest").isTrue();
        assertThat(store.readVersioned("oci/app/tags/1.0"))
                .as("the tag is linked to the verified digest").isPresent();
    }

    @Test
    void a_proxied_manifest_by_tag_mismatching_the_content_digest_is_refused() throws IOException {
        byte[] tampered = ("{\"mediaType\":\"tampered\"}").getBytes(StandardCharsets.UTF_8);
        byte[] legit = ("{\"mediaType\":\"legit\"}").getBytes(StandardCharsets.UTF_8);
        // Upstream (or a corrupted proxy leg) hands over tampered bytes under a header claiming the legit digest.
        String claimed = "sha256:" + sha256(legit);
        AtomicInteger hits = new AtomicInteger();
        ProxyFormat.Fetcher fetcher = counting("/v2/app/manifests/1.0",
                new ProxyFormat.Fetched(200, tampered,
                        Map.of("Content-Type", MANIFEST_TYPE, "Docker-Content-Digest", claimed)), hits);

        FakeExchange pull = new FakeExchange("GET", "/v2/app/manifests/1.0",
                new byte[0], Map.of(), Map.of("Accept", MANIFEST_TYPE));
        boolean served = format.proxy(pull, store, UPSTREAM, fetcher);

        assertThat(served).as("a manifest whose bytes belie the Docker-Content-Digest is refused").isFalse();
        assertThat(store.exists("blobs/" + sha256(tampered))).as("tampered manifest is not cached").isFalse();
        assertThat(store.readVersioned("oci/app/tags/1.0")).as("the tag is not linked").isEmpty();

        // A re-pull re-hits upstream - nothing was cached or tagged.
        format.proxy(new FakeExchange("GET", "/v2/app/manifests/1.0",
                new byte[0], Map.of(), Map.of("Accept", MANIFEST_TYPE)), store, UPSTREAM, fetcher);
        assertThat(hits.get()).as("a re-pull re-hits upstream - nothing was cached").isEqualTo(2);
    }

    /** A store decorator that counts how many times the chunk directory ({@code oci/uploads/<id>}) is listed, so a
     *  test can prove a chunked upload's per-PATCH cost never re-scans the staged chunks. Everything else delegates. */
    private static final class CountingUploadsList implements ArtifactStore {

        private final ArtifactStore delegate;
        private int uploadsListings;

        private CountingUploadsList(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        private void reset() {
            uploadsListings = 0;
        }

        private int uploadsListings() {
            return uploadsListings;
        }

        @Override
        public List<String> list(String prefix) {
            if (prefix.startsWith("oci/uploads/")) {
                uploadsListings++;
            }
            return delegate.list(prefix);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
