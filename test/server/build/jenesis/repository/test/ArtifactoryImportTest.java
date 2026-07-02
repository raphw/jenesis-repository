package build.jenesis.repository.test;

import build.jenesis.repository.proxy.HttpFetcher;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.importer.artifactory.ArtifactorySource;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Artifactory connector of an import, complementing the Nexus one: a fake Artifactory (a JDK HTTP
 * server) answers the storage listing API ({@code GET /api/storage/<repo>?list&deep=1}) with a deep file list and
 * serves each file at {@code /<repo>/<path>}. The {@link RepositoryImport} walks it through an
 * {@link ArtifactorySource} - whose listing carries no per-file format, so the repository's package type is given -
 * and the real {@link RepositoryApplication} then serves the migrated Maven jar, its module view and the regenerated
 * metadata, with the listing's folder entries skipped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ArtifactoryImportTest {

    @TempDir
    static Path root;

    private HttpServer artifactory;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private byte[] jar;
    private RepositoryImport.Result result;

    @BeforeAll
    public void setUp() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);

        jar = "a library jar".getBytes(StandardCharsets.UTF_8);
        byte[] pom = "<project><modelVersion>4.0.0</modelVersion></project>".getBytes(StandardCharsets.UTF_8);

        artifactory = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String upstream = "http://localhost:" + artifactory.getAddress().getPort();

        Map<String, byte[]> files = new HashMap<>();
        files.put("/libs-release/org/lib/dep/2.0/dep-2.0.jar", jar);
        files.put("/libs-release/org/lib/dep/2.0/dep-2.0.pom", pom);

        String listing = "{\"repo\":\"libs-release\",\"files\":["
                + "{\"uri\":\"/org/lib/dep\",\"folder\":true},"
                + "{\"uri\":\"/org/lib/dep/2.0/dep-2.0.jar\",\"folder\":false},"
                + "{\"uri\":\"/org/lib/dep/2.0/dep-2.0.pom\",\"folder\":false}]}";

        artifactory.createContext("/api/storage/libs-release", exchange ->
                respond(exchange, 200, listing.getBytes(StandardCharsets.UTF_8)));
        artifactory.createContext("/libs-release", exchange -> {
            byte[] body = files.get(exchange.getRequestURI().getPath());
            respond(exchange, body == null ? 404 : 200, body == null ? new byte[0] : body);
        });
        artifactory.start();

        result = new RepositoryImport().run(
                new ArtifactorySource(URI.create(upstream), "libs-release", "maven", new HttpFetcher()), store);

        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void tearDown() {
        running.close();
        artifactory.stop(0);
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void the_two_files_are_imported_and_the_folder_skipped() throws Exception {
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        assertThat(bytes("/maven/org/lib/dep/2.0/dep-2.0.jar")).isEqualTo(jar);
        assertThat(new String(bytes("/maven/org/lib/dep/2.0/dep-2.0.pom"), StandardCharsets.UTF_8))
                .contains("modelVersion");
    }

    @Test
    public void the_coordinate_metadata_is_regenerated() throws Exception {
        assertThat(new String(bytes("/maven/org/lib/dep/maven-metadata.xml"), StandardCharsets.UTF_8))
                .contains("<version>2.0</version>");
    }

    private byte[] bytes(String path) throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
        return response.body();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (body.length > 0) {
                out.write(body);
            }
        }
    }
}
