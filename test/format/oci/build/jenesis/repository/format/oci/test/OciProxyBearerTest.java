package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OCI Distribution bearer-token auth flow the proxy uses (OciFormat.fetch/download): the upstream first answers
 * {@code 401} with a {@code Bearer} {@code WWW-Authenticate} challenge, the realm is exchanged for a token, and the
 * request is retried once carrying {@code Authorization: Bearer <token>}. Both the buffered manifest fetch and the
 * streamed blob download ride this flow; answered from a fixed in-memory registry, no network.
 */
class OciProxyBearerTest {

    private static final URI UPSTREAM = URI.create("https://registry.example");
    private static final String CHALLENGE =
            "Bearer realm=\"https://auth.example/token\",service=\"registry.example\",scope=\"repository:app:pull\"";

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
    void a_manifest_pull_answers_a_401_bearer_challenge_and_is_served_after_the_token_exchange() throws IOException {
        String type = "application/vnd.oci.image.manifest.v1+json";
        byte[] manifest = ("{\"mediaType\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8);
        List<String> presented = new ArrayList<>();
        ProxyFormat.Fetcher fetcher = (url, headers) -> {
            if (url.toString().startsWith("https://auth.example/token")) {
                return Optional.of(new ProxyFormat.Fetched(200,
                        "{\"token\":\"TKN-123\"}".getBytes(StandardCharsets.UTF_8), Map.of()));
            }
            if (headers.containsKey("Authorization")) {
                presented.add(headers.get("Authorization"));
                return Optional.of(new ProxyFormat.Fetched(200, manifest, Map.of("Content-Type", type)));
            }
            return Optional.of(new ProxyFormat.Fetched(401, new byte[0], Map.of("WWW-Authenticate", CHALLENGE)));
        };

        FakeExchange get = new FakeExchange("GET", "/v2/app/manifests/1.0", new byte[0],
                Map.of(), Map.of("Accept", type));
        boolean served = format.proxy(get, store, UPSTREAM, fetcher);

        assertThat(served).as("the manifest is served after the bearer exchange").isTrue();
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(manifest);
        assertThat(presented).as("the retried request carried the exchanged token")
                .containsExactly("Bearer TKN-123");
        assertThat(store.readVersioned("oci/app/tags/1.0")).as("the tag pointer was recorded").isPresent();
    }

    @Test
    void a_blob_pull_rides_the_same_bearer_flow_over_the_streamed_download() throws IOException {
        byte[] layer = "layer-bytes-over-bearer".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(layer);
        List<String> presented = new ArrayList<>();
        ProxyFormat.Fetcher fetcher = new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                // The realm exchange is a buffered fetch even when the blob itself streams.
                return Optional.of(new ProxyFormat.Fetched(200,
                        "{\"token\":\"TKN-blob\"}".getBytes(StandardCharsets.UTF_8), Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) {
                if (headers.containsKey("Authorization")) {
                    presented.add(headers.get("Authorization"));
                    return Optional.of(new ProxyFormat.Download(200, new ByteArrayInputStream(layer), Map.of()));
                }
                return Optional.of(new ProxyFormat.Download(401, new ByteArrayInputStream(new byte[0]),
                        Map.of("WWW-Authenticate", CHALLENGE)));
            }
        };

        FakeExchange get = new FakeExchange("GET", "/v2/app/blobs/sha256:" + hex);
        boolean served = format.proxy(get, store, UPSTREAM, fetcher);

        assertThat(served).as("the blob is served after the bearer exchange").isTrue();
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(layer);
        assertThat(presented).as("the retried download carried the exchanged token")
                .containsExactly("Bearer TKN-blob");
        assertThat(store.exists("blobs/" + hex)).as("the fetched layer is cached content-addressed").isTrue();
    }
}
