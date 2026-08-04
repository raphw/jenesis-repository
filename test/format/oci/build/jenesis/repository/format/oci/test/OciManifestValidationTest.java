package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manifest validation at the {@code OciManifests.ingest} choke point (Audit-26 F4): a body that is not a servable
 * manifest - larger than {@code MAX_MANIFEST} (4 MiB) or not parseable as a JSON object - must never be ingested as a
 * servable manifest, so it cannot later be laid out and then half-held (a malformed/over-cap manifest degrades
 * {@code OciBlobLayout.blobHashes} to manifest-only, leaving its named layers servable by digest under a standing hold).
 * The PUT edge refuses with {@code 400 MANIFEST_INVALID}; the proxy edge serves the upstream body through WITHOUT
 * caching (the > 4 MiB path is reachable only on the proxy leg, bounded by the 64 MiB fetch cap, not the 4 MiB PUT cap).
 */
class OciManifestValidationTest {

    private static final URI UPSTREAM = URI.create("https://registry.example");
    private static final String TYPE = "application/vnd.oci.image.manifest.v1+json";
    /** Mirror of MAX_MANIFEST (package-private): the 4 MiB manifest cap. */
    private static final int MAX_MANIFEST = 4 * 1024 * 1024;

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

    private int put(String name, String reference, byte[] manifest) throws IOException {
        FakeExchange exchange = new FakeExchange("PUT", "/v2/" + name + "/manifests/" + reference, manifest,
                Map.of(), Map.of("Content-Type", TYPE));
        format.handle(exchange, store);
        return exchange.status();
    }

    @Test
    void a_put_of_a_non_json_manifest_is_refused_400_and_nothing_is_stored() throws IOException {
        byte[] garbage = "this is not a json manifest".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(garbage);

        assertThat(put("app", "1.0", garbage)).as("a malformed manifest is refused MANIFEST_INVALID").isEqualTo(400);
        assertThat(store.exists("blobs/" + hex)).as("the malformed body is never content-addressed").isFalse();
        assertThat(store.readVersioned("oci/app/tags/1.0")).as("no tag pointer is laid out").isEmpty();

        FakeExchange get = new FakeExchange("GET", "/v2/app/manifests/1.0");
        format.handle(get, store);
        assertThat(get.status()).as("the refused manifest does not serve").isEqualTo(404);
    }

    @Test
    void a_put_of_a_json_array_top_level_is_refused_because_it_is_not_a_manifest_object() throws IOException {
        // Parseable JSON but not a manifest shape: a top-level array has no config/layers to enumerate, the exact
        // half-ingest the guard prevents. Refused, storing nothing.
        byte[] array = "[{\"digest\":\"sha256:00\"}]".getBytes(StandardCharsets.UTF_8);
        assertThat(put("app", "1.0", array)).as("a non-object JSON manifest is refused").isEqualTo(400);
        assertThat(store.exists("blobs/" + sha256(array))).isFalse();
    }

    @Test
    void a_put_of_a_valid_manifest_still_ingests_and_serves() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\",\"config\":{\"digest\":\"sha256:aa\"},\"layers\":[]}")
                .getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        assertThat(put("app", "1.0", manifest)).as("a valid manifest ingests (201)").isEqualTo(201);
        assertThat(store.exists("blobs/" + hex)).as("the valid manifest is stored").isTrue();

        FakeExchange get = new FakeExchange("GET", "/v2/app/manifests/1.0");
        format.handle(get, store);
        assertThat(get.status()).as("the valid manifest serves").isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(manifest);
    }

    @Test
    void a_put_over_the_4_mib_cap_is_refused_by_the_edge_pre_cap() throws IOException {
        // The PUT edge pre-caps the request stream at MAX_MANIFEST and refuses an overflow with 413 before ingest is
        // even reached - pinned here as the edge belt (the ingest choke-point size check backstops the proxy leg).
        byte[] oversize = new byte[MAX_MANIFEST + 16];
        Arrays.fill(oversize, (byte) '{');
        assertThat(put("app", "1.0", oversize)).as("an oversize PUT is refused at the edge").isEqualTo(413);
    }

    @Test
    void a_proxied_oversize_manifest_is_served_through_without_caching() throws IOException {
        // The > 4 MiB gap the ingest size check closes: a proxied manifest is bounded only by the 64 MiB fetch cap, so a
        // > 4 MiB (but otherwise valid-JSON) body reaches ingest. It must be served through to the client but never
        // cached/laid out - nothing stored means nothing that can later need an un-enumerable hold.
        byte[] filler = new byte[MAX_MANIFEST + 32];
        Arrays.fill(filler, (byte) 'a');
        byte[] body = ("{\"pad\":\"" + new String(filler, StandardCharsets.UTF_8) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        String hex = sha256(body);
        List<URI> fetched = new ArrayList<>();
        ProxyFormat.Fetcher fetcher = (url, headers) -> {
            fetched.add(url);
            return Optional.of(new ProxyFormat.Fetched(200, body, Map.of("Content-Type", TYPE)));
        };

        FakeExchange get = new FakeExchange("GET", "/v2/app/manifests/1.0", new byte[0],
                Map.of(), Map.of("Accept", TYPE));
        boolean served = format.proxy(get, store, UPSTREAM, fetcher);

        assertThat(served).as("the oversize upstream manifest is served through").isTrue();
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).as("the client still receives the upstream body").isEqualTo(body);
        assertThat(store.exists("blobs/" + hex)).as("nothing is cached content-addressed").isFalse();
        assertThat(store.readVersioned("oci/app/tags/1.0")).as("no tag pointer is laid out").isEmpty();
    }

    @Test
    void a_proxied_non_json_manifest_is_served_through_without_caching() throws IOException {
        byte[] body = "not-json-from-upstream".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(body);
        ProxyFormat.Fetcher fetcher = (url, headers) ->
                Optional.of(new ProxyFormat.Fetched(200, body, Map.of("Content-Type", TYPE)));

        FakeExchange get = new FakeExchange("GET", "/v2/app/manifests/1.0", new byte[0],
                Map.of(), Map.of("Accept", TYPE));
        boolean served = format.proxy(get, store, UPSTREAM, fetcher);

        assertThat(served).as("the malformed upstream manifest is served through").isTrue();
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(body);
        assertThat(store.exists("blobs/" + hex)).as("nothing is cached content-addressed").isFalse();
        assertThat(store.readVersioned("oci/app/tags/1.0")).as("no tag pointer is laid out").isEmpty();
    }
}
