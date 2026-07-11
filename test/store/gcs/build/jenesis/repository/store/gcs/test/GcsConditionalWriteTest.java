package build.jenesis.repository.store.gcs.test;

import module java.base;
import module jdk.httpserver;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the GCS-specific conditional-write protocol without a network: a generation-aware in-process
 * stub ({@code jdk.httpserver}) implements exactly the slice of the GCS XML API the backend's versioned
 * operations touch - it stores objects with a monotonically increasing generation, answers reads with
 * the {@code x-goog-generation} header, and enforces the {@code x-goog-if-generation-match} PUT
 * precondition ({@code 0} = only-if-absent) with a {@code 412} - while the real SDK client drives it
 * through {@link ArtifactStoreProvider#resolve}. That pins the wire contract the MinIO leg cannot
 * (MinIO ignores {@code x-goog} headers): the create-if-absent and update-if-unchanged writes send the
 * precondition, a rejection maps to a {@code false} return rather than an exception, and the version
 * token round-trips as the object generation, not the ETag. The missing-bucket configuration error is
 * asserted here too, as this suite needs no Docker daemon and always runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GcsConditionalWriteTest {

    private HttpServer server;
    private ArtifactStore store;
    private final Map<String, Stored> objects = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    private record Stored(byte[] content, long generation) {
    }

    @BeforeAll
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        Map<String, String> values = Map.of(
                "JENESIS_GCS_BUCKET", "repo",
                "JENESIS_GCS_ENDPOINT", "http://localhost:" + server.getAddress().getPort(),
                "JENESIS_GCS_ACCESS_KEY_ID", "hmac-access",
                "JENESIS_GCS_SECRET_ACCESS_KEY", "hmac-secret");
        store = ArtifactStoreProvider.resolve("gcs", values::get).scope("acme");
    }

    @AfterAll
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (!path.startsWith("/repo")) {
                exchange.sendResponseHeaders(501, -1);
                return;
            }
            String key = path.length() > "/repo/".length() ? path.substring("/repo/".length()) : "";
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    if (key.isEmpty()) {
                        exchange.sendResponseHeaders(200, -1); // The provider's create-bucket attempt.
                        return;
                    }
                    Stored existing = objects.get(key);
                    String precondition = exchange.getRequestHeaders().getFirst("x-goog-if-generation-match");
                    if (precondition != null && (precondition.equals("0")
                            ? existing != null
                            : existing == null || !precondition.equals(Long.toString(existing.generation())))) {
                        respond(exchange, 412, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error>"
                                + "<Code>PreconditionFailed</Code><Message>generation mismatch</Message></Error>");
                        return;
                    }
                    Stored stored = new Stored(body, generations.incrementAndGet());
                    objects.put(key, stored);
                    exchange.getResponseHeaders().set("x-goog-generation", Long.toString(stored.generation()));
                    exchange.getResponseHeaders().set("ETag", "\"stub-" + stored.generation() + "\"");
                    exchange.sendResponseHeaders(200, -1);
                }
                case "GET" -> {
                    Stored existing = objects.get(key);
                    if (existing == null) {
                        respond(exchange, 404, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error>"
                                + "<Code>NoSuchKey</Code><Message>absent</Message></Error>");
                        return;
                    }
                    exchange.getResponseHeaders().set("x-goog-generation", Long.toString(existing.generation()));
                    exchange.getResponseHeaders().set("ETag", "\"stub-" + existing.generation() + "\"");
                    exchange.sendResponseHeaders(200, existing.content().length);
                    exchange.getResponseBody().write(existing.content());
                }
                default -> exchange.sendResponseHeaders(501, -1);
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
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
    public void a_missing_bucket_setting_is_a_clear_configuration_error() {
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve("gcs", key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JENESIS_GCS_BUCKET");
    }
}
