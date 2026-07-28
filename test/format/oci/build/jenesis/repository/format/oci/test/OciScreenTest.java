package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.format.oci.OciImporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OCI manifest choke point (EPIC 26): a manifest write - a push PUT, a pull-through proxy fetch, or an import -
 * runs the same discovered {@link build.jenesis.repository.store.PublishInterceptor} screen a single-body publish does,
 * mapped onto OCI's native {@code withheld/<hex>} marker. The discovered {@link OciScreenInterceptor} rejects a
 * coordinate carrying {@code gate-reject} and quarantines one carrying {@code gate-quarantine}; every other coordinate
 * is inert, so an accepted push is unchanged. A withheld manifest 404s by digest and by tag, while its layer blobs -
 * out of the choke point's scope by design - upload and serve raw.
 */
class OciScreenTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final OciFormat format = new OciFormat();
    private final OciImporter importer = new OciImporter();

    private static final String TYPE = "application/vnd.oci.image.manifest.v1+json";

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

    private int pushManifest(String name, String reference, byte[] manifest) throws IOException {
        FakeExchange put = new FakeExchange("PUT", "/v2/" + name + "/manifests/" + reference, manifest,
                Map.of(), Map.of("Content-Type", TYPE));
        format.handle(put, store);
        return put.status();
    }

    private int pushBlob(String name, byte[] blob) throws IOException {
        FakeExchange post = new FakeExchange("POST", "/v2/" + name + "/blobs/uploads/", blob,
                Map.of("digest", "sha256:" + sha256(blob)), Map.of());
        format.handle(post, store);
        return post.status();
    }

    private int getStatus(String path) throws IOException {
        FakeExchange get = new FakeExchange("GET", path);
        format.handle(get, store);
        return get.status();
    }

    @Test
    void a_rejected_manifest_is_denied_and_unpullable_while_its_layers_stay_raw() throws IOException {
        byte[] layer = "layer-bytes".getBytes(StandardCharsets.UTF_8);
        String layerHex = sha256(layer);
        // A layer blob uploads fine - blobs are out of the choke point's scope, served raw by digest.
        assertThat(pushBlob("gate-reject/app", layer)).as("a layer blob uploads raw").isEqualTo(201);
        assertThat(getStatus("/v2/gate-reject/app/blobs/sha256:" + layerHex))
                .as("the raw layer blob serves").isEqualTo(200);

        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\",\"layers\":[{\"digest\":\"sha256:"
                + layerHex + "\"}]}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        FakeExchange put = new FakeExchange("PUT", "/v2/gate-reject/app/manifests/1.0", manifest,
                Map.of(), Map.of("Content-Type", TYPE));
        format.handle(put, store);
        assertThat(put.status()).as("a rejected manifest push is 403 DENIED").isEqualTo(403);
        assertThat(put.responseText()).contains("\"code\":\"DENIED\"");

        // The withheld marker is set on the manifest's serving key, so it 404s by digest AND by tag though its bytes
        // were stored (screen stored blobs/<hex> before the gate ran).
        assertThat(store.exists("blobs/" + hex)).as("screen stored the manifest bytes").isTrue();
        assertThat(store.exists("withheld/" + hex)).as("the native withhold marker is set").isTrue();
        assertThat(store.exists("oci/types/" + hex)).as("no media-type sidecar for a rejected manifest").isFalse();
        assertThat(getStatus("/v2/gate-reject/app/manifests/sha256:" + hex))
                .as("the rejected manifest is unpullable by digest").isEqualTo(404);
        assertThat(getStatus("/v2/gate-reject/app/manifests/1.0"))
                .as("the rejected manifest is unpullable by tag").isEqualTo(404);

        // The layer blob still serves raw - the manifest verdict never touched it.
        assertThat(getStatus("/v2/gate-reject/app/blobs/sha256:" + layerHex))
                .as("layers stay raw and served even when their manifest is rejected").isEqualTo(200);
    }

    @Test
    void a_quarantined_manifest_is_held_and_unpullable() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        assertThat(pushManifest("gate-quarantine/app", "1.0", manifest))
                .as("a quarantined manifest push is 202").isEqualTo(202);
        assertThat(store.exists("withheld/" + hex)).as("the native withhold marker is set").isTrue();
        assertThat(getStatus("/v2/gate-quarantine/app/manifests/sha256:" + hex))
                .as("a held manifest is unpullable by digest").isEqualTo(404);
        assertThat(getStatus("/v2/gate-quarantine/app/manifests/1.0"))
                .as("a held manifest is unpullable by tag").isEqualTo(404);
    }

    @Test
    void an_accepted_manifest_is_unchanged_and_clears_a_stale_marker() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);
        // Pre-plant a stale withhold marker, as if this content had been withheld earlier; an accepted push clears it.
        store.write("withheld/" + hex, new ByteArrayInputStream("REJECT".getBytes(StandardCharsets.UTF_8)));

        assertThat(pushManifest("app", "1.0", manifest)).as("an accepted push is 201").isEqualTo(201);
        assertThat(store.exists("withheld/" + hex)).as("the stale withhold marker is cleared").isFalse();

        FakeExchange byTag = new FakeExchange("GET", "/v2/app/manifests/1.0");
        format.handle(byTag, store);
        assertThat(byTag.status()).as("an accepted manifest pulls by tag").isEqualTo(200);
        assertThat(byTag.responseBytes()).isEqualTo(manifest);
        assertThat(getStatus("/v2/app/manifests/sha256:" + hex))
                .as("an accepted manifest pulls by digest").isEqualTo(200);
    }

    @Test
    void an_imported_manifest_routes_through_the_same_choke_point() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        importer.importArtifact("v2/gate-reject/app/manifests/1.0",
                new ByteArrayInputStream(manifest), store);

        assertThat(store.exists("blobs/" + hex)).as("screen stored the imported manifest bytes").isTrue();
        assertThat(store.exists("withheld/" + hex)).as("a rejected import is withheld").isTrue();
        assertThat(store.exists("oci/types/" + hex)).as("no sidecar for a rejected import").isFalse();
        assertThat(getStatus("/v2/gate-reject/app/manifests/sha256:" + hex))
                .as("a rejected imported manifest is unpullable by digest").isEqualTo(404);
    }

    @Test
    void a_proxied_manifest_routes_through_the_same_choke_point() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);
        var fetcher = (build.jenesis.repository.format.ProxyFormat.Fetcher) (url, headers) ->
                java.util.Optional.of(new build.jenesis.repository.format.ProxyFormat.Fetched(
                        200, manifest, Map.of("Content-Type", TYPE)));

        FakeExchange pull = new FakeExchange("GET", "/v2/gate-reject/app/manifests/1.0");
        boolean claimed = format.proxy(pull, store, java.net.URI.create("http://upstream.local"), fetcher);

        assertThat(claimed).as("the proxy claimed the manifest pull").isTrue();
        assertThat(store.exists("withheld/" + hex)).as("a rejected proxied manifest is withheld").isTrue();
        assertThat(pull.status()).as("a rejected proxied manifest 404s to the puller").isEqualTo(404);
    }
}
