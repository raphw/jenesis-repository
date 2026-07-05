package build.jenesis.repository.test;

import build.jenesis.repository.importer.jenesis.JenesisSource;
import build.jenesis.repository.proxy.HttpFetcher;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the exit story end to end: a running jenesis instance is walked through its own {@code /api/assets}
 * enumeration and migrated - server to store - into a fresh content-addressed store, the outbound mirror of the
 * incumbent importers, with no lock-in. A source {@link RepositoryApplication} is booted over a seeded store; the
 * {@link RepositoryImport} drives a {@link JenesisSource} over real HTTP (the pull walk plus the per-asset
 * download), and the target store serves back exactly the bytes that went in - a Maven artifact re-laid under
 * {@code /maven/} and a raw file kept verbatim under {@code /raw/}. The same walk is exercised at the wire level to
 * show each entry carries its pointer metadata (path, size, SHA-256, coordinate) and pages by an opaque cursor,
 * without ever opening a blob to answer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JenesisRoundTripTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path sourceRoot;
    @TempDir
    static Path targetRoot;

    private RepositoryApplication.Running source;
    private HttpClient client;
    private String base;

    private byte[] pom;
    private byte[] rawFile;
    private ArtifactStore target;
    private RepositoryImport.Result result;

    @BeforeAll
    public void setUp() throws IOException {
        pom = "<project><artifactId>app</artifactId><version>1.0</version></project>".getBytes(StandardCharsets.UTF_8);
        rawFile = "a signed installer".getBytes(StandardCharsets.UTF_8);

        // Seed the source's default/default space directly, then boot the server over it, so the enumeration walks a
        // known set of publication pointers (a Maven pom, no module view; a raw file).
        System.setProperty("JENESIS_STORE_ROOT", sourceRoot.toString());
        ArtifactStore sourceStore = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? sourceRoot.toString() : null)
                .scope("default").scope("default");
        Publication seed = new Publication(sourceStore);
        seed.link("/maven/com/acme/app/1.0/app-1.0.pom", seed.storeBlob(new ByteArrayInputStream(pom)));
        seed.link("/raw/tools/installer.bin", seed.storeBlob(new ByteArrayInputStream(rawFile)));

        source = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + source.port();

        // Migrate the running source into a fresh, independent store over real HTTP.
        target = ArtifactStoreProvider.resolve("filesystem", key -> targetRoot.toString())
                .scope("default").scope("default");
        result = new RepositoryImport().run(new JenesisSource(URI.create(base), "default", new HttpFetcher()), target);
    }

    @AfterAll
    public void tearDown() {
        if (source != null) {
            source.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void a_jenesis_repository_round_trips_into_another_store() throws IOException {
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        assertThat(result.skippedFormats()).isEmpty();

        assertThat(served(target, "/maven/com/acme/app/1.0/app-1.0.pom")).isEqualTo(pom);
        assertThat(served(target, "/raw/tools/installer.bin")).isEqualTo(rawFile);
    }

    @Test
    public void the_endpoint_pages_assets_with_pointer_metadata() throws Exception {
        JsonNode first = assets("/api/assets?repo=default&limit=1");
        assertThat(first.path("assets")).hasSize(1);
        JsonNode maven = first.path("assets").get(0);
        assertThat(maven.path("path").asString()).isEqualTo("/maven/com/acme/app/1.0/app-1.0.pom");
        assertThat(maven.path("format").asString()).isEqualTo("maven");
        assertThat(maven.path("ecosystem").asString()).isEqualTo("Maven");
        assertThat(maven.path("coordinate").asString()).isEqualTo("com.acme:app");
        assertThat(maven.path("version").asString()).isEqualTo("1.0");
        assertThat(maven.path("size").asLong()).isEqualTo(pom.length);
        assertThat(maven.path("sha256").asString()).isEqualTo(sha256(pom));
        String cursor = first.path("cursor").asString(null);
        assertThat(cursor).isNotNull();

        JsonNode second = assets("/api/assets?repo=default&limit=1&cursor=" + cursor);
        assertThat(second.path("assets")).hasSize(1);
        JsonNode raw = second.path("assets").get(0);
        assertThat(raw.path("path").asString()).isEqualTo("/raw/tools/installer.bin");
        assertThat(raw.path("format").asString()).isEqualTo("raw");
        assertThat(raw.path("size").asLong()).isEqualTo(rawFile.length);
        assertThat(raw.path("sha256").asString()).isEqualTo(sha256(rawFile));
        assertThat(second.path("cursor").isNull()).isTrue();
    }

    @Test
    public void an_unknown_repository_is_an_empty_walk() throws Exception {
        JsonNode body = assets("/api/assets?repo=missing");
        assertThat(body.path("assets")).isEmpty();
        assertThat(body.path("cursor").isNull()).isTrue();
    }

    @Test
    public void a_traversal_repository_name_is_rejected() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/assets?repo=..%2Fother")).GET().build(),
                BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    private JsonNode assets(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static byte[] served(ArtifactStore store, String path) throws IOException {
        Optional<String> key = new Publication(store).located(path);
        assertThat(key).as("published " + path).isPresent();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read(key.get(), out);
        return out.toByteArray();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
