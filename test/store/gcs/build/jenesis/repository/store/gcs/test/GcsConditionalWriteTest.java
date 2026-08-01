package build.jenesis.repository.store.gcs.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.gcs.GcsArtifactStoreProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the GCS-specific conditional-write protocol without a network: a generation-aware WireMock stub (a
 * {@code ResponseDefinitionTransformerV2}) implements exactly the slice of the GCS XML API the backend's versioned
 * operations touch - it stores objects with a monotonically increasing generation, answers reads with the
 * {@code x-goog-generation} header, and enforces the {@code x-goog-if-generation-match} PUT precondition ({@code 0} =
 * only-if-absent) with a {@code 412} - while the real SDK client drives it through {@link ArtifactStoreProvider#resolve}.
 * That pins the wire contract the MinIO leg cannot (MinIO ignores {@code x-goog} headers): the create-if-absent and
 * update-if-unchanged writes send the precondition, a rejection maps to a {@code false} return rather than an exception,
 * and the version token round-trips as the object generation, not the ETag. The missing-bucket configuration error is
 * asserted here too, as this suite needs no Docker daemon and always runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GcsConditionalWriteTest {

    private WireMockServer server;
    private ArtifactStore store;
    private final Map<String, Stored> objects = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    private record Stored(byte[] content, long generation) {
    }

    @BeforeAll
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort()
                .extensions(new GcsProtocol(objects, generations)));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()));
        Map<String, String> values = Map.of(
                "JENESIS_GCS_BUCKET", "repo",
                "JENESIS_GCS_ENDPOINT", "http://localhost:" + server.port(),
                // The in-process stub speaks plaintext http, so opt past the https-endpoint secure default.
                "JENESIS_GCS_ALLOW_INSECURE_ENDPOINT", "true",
                "JENESIS_GCS_ACCESS_KEY_ID", "hmac-access",
                "JENESIS_GCS_SECRET_ACCESS_KEY", "hmac-secret");
        store = ArtifactStoreProvider.resolve("gcs", values::get).scope("acme");
    }

    @AfterAll
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void write_versioned_is_a_create_if_absent_compare_and_set() throws IOException {
        String key = "config/create";
        assertThat(store.readVersioned(key)).isEmpty();
        assertThat(store.writeVersioned(key, "one".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThat(store.writeVersioned(key, "two".getBytes(StandardCharsets.UTF_8), null)).isFalse();
        ArtifactStore.Versioned stored = store.readVersioned(key).orElseThrow();
        assertThat(new String(stored.content(), StandardCharsets.UTF_8)).isEqualTo("one");
    }

    @Test
    public void write_versioned_is_an_update_if_unchanged_compare_and_set() throws IOException {
        String key = "config/update";
        assertThat(store.writeVersioned(key, "v1".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object token = store.readVersioned(key).orElseThrow().token();
        assertThat(store.writeVersioned(key, "v2".getBytes(StandardCharsets.UTF_8), token)).isTrue();
        assertThat(store.writeVersioned(key, "v3".getBytes(StandardCharsets.UTF_8), token)).isFalse();
        assertThat(new String(store.readVersioned(key).orElseThrow().content(), StandardCharsets.UTF_8)).isEqualTo("v2");
    }

    @Test
    public void the_version_token_is_the_object_generation() throws IOException {
        String key = "config/token";
        assertThat(store.writeVersioned(key, "a".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object first = store.readVersioned(key).orElseThrow().token();
        assertThat(store.writeVersioned(key, "b".getBytes(StandardCharsets.UTF_8), first)).isTrue();
        Object second = store.readVersioned(key).orElseThrow().token();
        // The token is the x-goog-generation the stub advanced on the second write - not the ETag it also sends.
        assertThat(second).isNotEqualTo(first);
        assertThat(objects).containsKey("acme/" + key);
        assertThat(second).isEqualTo(Long.toString(objects.get("acme/" + key).generation()));
    }

    @Test
    public void read_versioned_fails_fast_when_the_endpoint_omits_the_generation_header() {
        // The version token is the object generation, carried in the x-goog-generation response header; an endpoint
        // that omits it (a generic S3-compatible store mistakenly pointed at the gcs backend) must surface a clear
        // IOException naming the missing header, never fabricate a token that would later mis-compare.
        assertThatThrownBy(() -> store.readVersioned("no-generation-header"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("x-goog-generation");
    }

    @Test
    public void open_streams_a_stored_blob_back_and_a_missing_key_throws() throws IOException {
        byte[] body = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
        assertThat(store.writeVersioned("blobs/opened", body, null)).isTrue();
        try (InputStream in = store.open("blobs/opened")) {
            assertThat(in.readAllBytes()).as("open() streams the stored bytes back").isEqualTo(body);
        }
        assertThatThrownBy(() -> store.open("blobs/absent"))
                .as("open() on a missing key surfaces an IOException, never a silent empty stream")
                .isInstanceOf(IOException.class);
    }

    @Test
    public void a_plaintext_endpoint_is_refused_unless_opted_in() {
        // The endpoint override must be https by default so the HMAC secret is never sent over plaintext; a http
        // emulator endpoint is refused with a clear error unless JENESIS_GCS_ALLOW_INSECURE_ENDPOINT opts in.
        assertThatThrownBy(() -> GcsArtifactStoreProvider.secureEndpoint("http://localhost:9000", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https")
                .hasMessageContaining("JENESIS_GCS_ALLOW_INSECURE_ENDPOINT");
        assertThat(GcsArtifactStoreProvider.secureEndpoint("http://localhost:9000", "true"))
                .as("the opt-out permits a plaintext emulator endpoint")
                .isEqualTo(URI.create("http://localhost:9000"));
        assertThat(GcsArtifactStoreProvider.secureEndpoint("https://storage.googleapis.com", null))
                .as("an https endpoint is always accepted")
                .isEqualTo(URI.create("https://storage.googleapis.com"));
    }

    @Test
    public void a_missing_bucket_setting_is_a_clear_configuration_error() {
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve("gcs", key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JENESIS_GCS_BUCKET");
    }

    @Test
    public void exists_and_size_fail_loud_on_a_non_404_head() {
        // The existence screen must distinguish absent (404 -> false / -1) from a backend fault: a 403 auth failure
        // (like a 503 throttle) has to surface, or a live artifact reads as a silent 404 miss and writeBlob's exists()
        // dedup probe could even skip re-uploading it during the outage. The stub answers a HEAD on "*/faulted" with a
        // 403, so exists() must throw (never return false) and size() must throw IOException (never return -1).
        assertThatThrownBy(() -> store.exists("blobs/faulted"))
                .as("a non-404 HEAD makes exists() fail loud, never report the object absent")
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.size("blobs/faulted"))
                .as("a non-404 HEAD makes size() throw, never return -1")
                .isInstanceOf(IOException.class);
    }

    @Test
    public void a_bucket_level_404_surfaces_as_a_transport_error_not_a_cas_conflict() {
        // A NoSuchBucket 404 is a misconfiguration or outage - a missing/renamed bucket - not the benign key-level
        // 404 an If-generation-match write treats as a CAS conflict. Mapping it to a false return would turn it into
        // silent retry-exhaustion; a versioned write must surface it as a real IOException.
        Map<String, String> values = Map.of(
                "JENESIS_GCS_BUCKET", "gone",
                "JENESIS_GCS_ENDPOINT", "http://localhost:" + server.port(),
                "JENESIS_GCS_ALLOW_INSECURE_ENDPOINT", "true",
                "JENESIS_GCS_ACCESS_KEY_ID", "hmac-access",
                "JENESIS_GCS_SECRET_ACCESS_KEY", "hmac-secret");
        ArtifactStore missing = ArtifactStoreProvider.resolve("gcs", values::get).scope("acme");
        assertThatThrownBy(() -> missing.writeVersioned("config/x", "v".getBytes(StandardCharsets.UTF_8), null))
                .as("a bucket-level 404 is a real transport/config failure, never a silent CAS conflict")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bucket");
    }

    /**
     * The generation-aware GCS XML wire the backend's versioned operations touch, expressed as a WireMock response
     * transformer over a shared {@code objects}/{@code generations} state the test also inspects. A 1:1 port of the
     * former hand-rolled {@code jdk.httpserver} handler: bucket-level 404, path/method routing, the
     * {@code x-goog-if-generation-match} precondition (0 = only-if-absent) enforced with a 412, and the
     * {@code x-goog-generation} token on reads and writes.
     */
    private static final class GcsProtocol implements ResponseDefinitionTransformerV2 {

        private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

        private final Map<String, Stored> objects;
        private final AtomicLong generations;

        private GcsProtocol(Map<String, Stored> objects, AtomicLong generations) {
            this.objects = objects;
            this.generations = generations;
        }

        @Override
        public String getName() {
            return "gcs";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }

        @Override
        public synchronized ResponseDefinition transform(ServeEvent event) {
            String url = event.getRequest().getUrl();
            String path = url.indexOf('?') < 0 ? url : url.substring(0, url.indexOf('?'));
            if (path.startsWith("/gone")) {
                return xml(404, "<Error><Code>NoSuchBucket</Code><Message>the bucket does not exist</Message></Error>");
            }
            if (!path.startsWith("/repo")) {
                return status(501);
            }
            String key = path.length() > "/repo/".length() ? path.substring("/repo/".length()) : "";
            RequestMethod method = event.getRequest().getMethod();
            if (RequestMethod.PUT.equals(method)) {
                if (key.isEmpty()) {
                    return status(200); // The provider's create-bucket attempt.
                }
                Stored existing = objects.get(key);
                String precondition = event.getRequest().getHeader("x-goog-if-generation-match");
                if (precondition != null && (precondition.equals("0")
                        ? existing != null
                        : existing == null || !precondition.equals(Long.toString(existing.generation())))) {
                    return xml(412, "<Error><Code>PreconditionFailed</Code><Message>generation mismatch</Message></Error>");
                }
                Stored stored = new Stored(event.getRequest().getBody(), generations.incrementAndGet());
                objects.put(key, stored);
                return aResponse().withStatus(200)
                        .withHeader("x-goog-generation", Long.toString(stored.generation()))
                        .withHeader("ETag", "\"stub-" + stored.generation() + "\"").build();
            }
            if (RequestMethod.GET.equals(method)) {
                if (key.endsWith("no-generation-header")) {
                    // A generic S3-compatible endpoint answers a GET with a body but no x-goog-generation header;
                    // readVersioned must fail fast rather than fabricate a token.
                    return aResponse().withStatus(200).withBody("present-but-headerless").build();
                }
                Stored existing = objects.get(key);
                if (existing == null) {
                    return xml(404, "<Error><Code>NoSuchKey</Code><Message>absent</Message></Error>");
                }
                return aResponse().withStatus(200)
                        .withHeader("x-goog-generation", Long.toString(existing.generation()))
                        .withHeader("ETag", "\"stub-" + existing.generation() + "\"")
                        .withBody(existing.content()).build();
            }
            if (RequestMethod.HEAD.equals(method)) {
                // exists()/size() issue a HEAD; only a 404 means absent, a non-404 (here a 403) must fail loud.
                return status(key.endsWith("faulted") ? 403 : 404);
            }
            return status(501);
        }

        private static ResponseDefinition status(int status) {
            return aResponse().withStatus(status).build();
        }

        private static ResponseDefinition xml(int status, String errorBody) {
            return aResponse().withStatus(status).withHeader("Content-Type", "application/xml")
                    .withBody(XML + errorBody).build();
        }
    }
}
