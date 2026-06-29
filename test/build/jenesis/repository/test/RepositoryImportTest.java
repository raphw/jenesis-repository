package build.jenesis.repository.test;

import build.jenesis.repository.NexusSource;
import build.jenesis.repository.RepositoryApplication;
import build.jenesis.repository.RepositoryImport;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
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
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a migration off an incumbent repository manager end to end, without a real Nexus or the public network. A
 * fake Nexus (a JDK HTTP server) answers the components REST API - paged by continuation token, mixed formats over
 * separate repositories - and serves the asset bytes. The {@link RepositoryImport} walks it through a
 * {@link NexusSource}, then the real {@link RepositoryApplication} is booted over the same store and the migrated
 * artifacts are pulled back over HTTP exactly as a build would: the Maven jar and its module view (so the
 * dual-layout bridge survives the import), the regenerated {@code maven-metadata.xml} (the source's copy is
 * discarded, not served), and the Docker manifest and layer. A npm repository is migrated by the same walk but,
 * with no npm importer on the module path, its assets are reported skipped and - because content is read lazily -
 * never even downloaded, which is the format split (coverage follows the importers present) made observable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryImportTest {

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    private static final String NPM_TARBALL = "/repository/npm-hosted/is-thirteen/-/is-thirteen-2.0.0.tgz";

    @TempDir
    static Path root;

    private HttpServer nexus;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private final Set<String> requested = Collections.synchronizedSet(new HashSet<>());

    private byte[] jar;
    private byte[] sourceMetadata;
    private byte[] manifest;
    private byte[] blob;
    private byte[] rawFile;
    private String blobDigest;
    private RepositoryImport.Result maven;
    private RepositoryImport.Result docker;
    private RepositoryImport.Result raw;
    private RepositoryImport.Result npm;

    @BeforeAll
    public void setUp() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);

        jar = jarWithModuleName("com.acme.app");
        byte[] pom = "<project><modelVersion>4.0.0</modelVersion></project>".getBytes(StandardCharsets.UTF_8);
        sourceMetadata = "<metadata>discarded-source-copy</metadata>".getBytes(StandardCharsets.UTF_8);
        blob = "a docker layer".getBytes(StandardCharsets.UTF_8);
        blobDigest = "sha256:" + sha256(blob);
        manifest = ("{\"schemaVersion\":2,\"mediaType\":\"" + OCI_MANIFEST + "\",\"layers\":[{\"digest\":\""
                + blobDigest + "\"}]}").getBytes(StandardCharsets.UTF_8);
        byte[] tarball = "a npm tarball".getBytes(StandardCharsets.UTF_8);
        rawFile = "a signed installer".getBytes(StandardCharsets.UTF_8);

        nexus = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String upstream = "http://localhost:" + nexus.getAddress().getPort();

        Map<String, byte[]> assets = new HashMap<>();
        assets.put("/repository/maven-releases/com/acme/app/1.0/app-1.0.jar", jar);
        assets.put("/repository/maven-releases/com/acme/app/1.0/app-1.0.pom", pom);
        assets.put("/repository/maven-releases/com/acme/app/maven-metadata.xml", sourceMetadata);
        assets.put("/repository/docker-hosted/v2/acme/img/manifests/v1", manifest);
        assets.put("/repository/docker-hosted/v2/acme/img/blobs/" + blobDigest, blob);
        assets.put("/repository/raw-hosted/tools/installer.bin", rawFile);
        assets.put(NPM_TARBALL, tarball);

        Map<String, String> pages = new HashMap<>();
        pages.put("maven-releases", "{\"items\":[{\"format\":\"maven2\",\"assets\":["
                + asset("com/acme/app/1.0/app-1.0.jar", upstream, "maven-releases") + ","
                + asset("com/acme/app/1.0/app-1.0.pom", upstream, "maven-releases")
                + "]}],\"continuationToken\":\"page2\"}");
        pages.put("maven-releases|page2", "{\"items\":[{\"format\":\"maven2\",\"assets\":["
                + asset("com/acme/app/maven-metadata.xml", upstream, "maven-releases")
                + "]}],\"continuationToken\":null}");
        pages.put("docker-hosted", "{\"items\":[{\"format\":\"docker\",\"assets\":["
                + asset("v2/acme/img/manifests/v1", upstream, "docker-hosted") + ","
                + asset("v2/acme/img/blobs/" + blobDigest, upstream, "docker-hosted")
                + "]}],\"continuationToken\":null}");
        pages.put("raw-hosted", "{\"items\":[{\"format\":\"raw\",\"assets\":["
                + asset("tools/installer.bin", upstream, "raw-hosted")
                + "]}],\"continuationToken\":null}");
        pages.put("npm-hosted", "{\"items\":[{\"format\":\"npm\",\"assets\":["
                + asset("is-thirteen/-/is-thirteen-2.0.0.tgz", upstream, "npm-hosted")
                + "]}],\"continuationToken\":null}");

        nexus.createContext("/repository", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            byte[] body = assets.get(exchange.getRequestURI().getPath());
            respond(exchange, body == null ? 404 : 200, body == null ? new byte[0] : body);
        });
        nexus.createContext("/service/rest/v1/components", exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String key = query.get("repository")
                    + (query.containsKey("continuationToken") ? "|" + query.get("continuationToken") : "");
            String page = pages.get(key);
            respond(exchange, page == null ? 404 : 200,
                    page == null ? new byte[0] : page.getBytes(StandardCharsets.UTF_8));
        });
        nexus.start();

        RepositoryImport importer = new RepositoryImport();
        maven = importer.run(new NexusSource(URI.create(upstream), "maven-releases"), store);
        docker = importer.run(new NexusSource(URI.create(upstream), "docker-hosted"), store);
        raw = importer.run(new NexusSource(URI.create(upstream), "raw-hosted"), store);
        npm = importer.run(new NexusSource(URI.create(upstream), "npm-hosted"), store);

        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port();
    }

    @AfterAll
    public void tearDown() {
        running.close();
        nexus.stop(0);
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void a_maven_repository_is_imported_and_served() throws Exception {
        assertThat(maven.imported()).isEqualTo(3);
        assertThat(maven.skipped()).isZero();
        assertThat(maven.skippedFormats()).isEmpty();
        assertThat(get("/maven/com/acme/app/1.0/app-1.0.jar")).isEqualTo(jar);
        assertThat(new String(get("/maven/com/acme/app/1.0/app-1.0.pom"), StandardCharsets.UTF_8))
                .contains("modelVersion");
    }

    @Test
    public void the_module_view_survives_the_import() throws Exception {
        assertThat(get("/module/com.acme.app/1.0/com.acme.app.jar")).isEqualTo(jar);
    }

    @Test
    public void maven_metadata_is_regenerated_not_copied() throws Exception {
        byte[] served = get("/maven/com/acme/app/maven-metadata.xml");
        assertThat(new String(served, StandardCharsets.UTF_8))
                .contains("<version>1.0</version>")
                .doesNotContain("discarded-source-copy");
        assertThat(served).isNotEqualTo(sourceMetadata);
    }

    @Test
    public void a_docker_image_is_imported_and_served() throws Exception {
        assertThat(docker.imported()).isEqualTo(2);
        assertThat(docker.skipped()).isZero();

        HttpRequest manifestRequest = HttpRequest.newBuilder(URI.create(base + "/v2/acme/img/manifests/v1"))
                .header("Accept", OCI_MANIFEST).GET().build();
        HttpResponse<byte[]> manifestResponse = client.send(manifestRequest, BodyHandlers.ofByteArray());
        assertThat(manifestResponse.statusCode()).isEqualTo(200);
        assertThat(manifestResponse.body()).isEqualTo(manifest);
        assertThat(manifestResponse.headers().firstValue("Content-Type")).contains(OCI_MANIFEST);

        assertThat(get("/v2/acme/img/blobs/" + blobDigest)).isEqualTo(blob);
    }

    @Test
    public void a_raw_repository_is_imported_and_served() throws Exception {
        assertThat(raw.imported()).isEqualTo(1);
        assertThat(raw.skipped()).isZero();
        assertThat(get("/raw/tools/installer.bin")).isEqualTo(rawFile);
    }

    @Test
    public void a_format_without_an_importer_is_skipped_and_not_downloaded() {
        assertThat(npm.imported()).isZero();
        assertThat(npm.skipped()).isEqualTo(1);
        assertThat(npm.skippedFormats()).containsExactly("npm");
        assertThat(requested).doesNotContain(NPM_TARBALL);
    }

    private byte[] get(String path) throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
        return response.body();
    }

    private static String asset(String path, String upstream, String repository) {
        return "{\"path\":\"" + path + "\",\"downloadUrl\":\""
                + upstream + "/repository/" + repository + "/" + path + "\"}";
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> parameters = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    parameters.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
        }
        return parameters;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (body.length > 0) {
                out.write(body);
            }
        }
    }

    private static byte[] jarWithModuleName(String module) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Automatic-Module-Name"), module);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new ZipEntry("com/acme/app/App.class"));
            jar.write("not really a class".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
