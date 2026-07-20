package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the vendor-neutral {@code maven} migration over HTTP against a plain JDK {@code HttpServer} serving a Maven
 * tree with generated autoindex pages - no Nexus, no Artifactory, no vendor API: the proof that any server exposing
 * the Maven layout is a migration source. A {@code POST /admin/import} walks the directory listing in the background
 * and the artifacts are then served, with metadata and checksum sidecars left behind; a walk whose subtree listing
 * fails once records a {@code tree:} cursor and a second {@code POST} naming the job resumes past the completed
 * subtree; and a URL whose host does not answer at all is rejected up front with a {@code 400}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MavenTreeImportTest {

    @TempDir
    static Path root;

    private HttpServer upstream;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private String url;
    private final Map<String, byte[]> files = new HashMap<>();
    private final AtomicBoolean betaFailedOnce = new AtomicBoolean();

    @BeforeAll
    public void setUp() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());

        files.put("/steady/org/acme/one/1.0/one-1.0.jar", "first jar".getBytes(StandardCharsets.UTF_8));
        files.put("/steady/org/acme/one/1.0/one-1.0.jar.sha1", "not imported".getBytes(StandardCharsets.UTF_8));
        files.put("/steady/org/acme/one/maven-metadata.xml", "<metadata/>".getBytes(StandardCharsets.UTF_8));
        files.put("/steady/org/acme/two/1.0/two-1.0.pom", "<project/>".getBytes(StandardCharsets.UTF_8));
        files.put("/flaky/alpha/a/1.0/a-1.0.jar", "alpha jar".getBytes(StandardCharsets.UTF_8));
        files.put("/flaky/beta/b/1.0/b-1.0.jar", "beta jar".getBytes(StandardCharsets.UTF_8));

        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        url = "http://localhost:" + upstream.getAddress().getPort();
        upstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] file = files.get(path);
            if (file != null) {
                respond(exchange, 200, file);
            } else if (path.endsWith("/")) {
                if (path.equals("/flaky/beta/") && betaFailedOnce.compareAndSet(false, true)) {
                    respond(exchange, 500, new byte[0]);
                } else {
                    listing(exchange, path);
                }
            } else {
                respond(exchange, 404, new byte[0]);
            }
        });
        upstream.start();

        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        // The import SSRF screen now blocks a loopback upstream by default; this test's fake Maven host is on
        // localhost, so pin the internal-host opt-out (the guard itself is proven by ImportHostGuardTest).
        System.setProperty("jenesis.repository.block-private-import-hosts", "false");
        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    /** A plain autoindex page: the direct children of the directory, each a relative link, as nginx would render. */
    private void listing(HttpExchange exchange, String path) throws IOException {
        TreeSet<String> children = new TreeSet<>();
        for (String key : files.keySet()) {
            if (key.startsWith(path)) {
                String rest = key.substring(path.length());
                int slash = rest.indexOf('/');
                children.add(slash < 0 ? rest : rest.substring(0, slash + 1));
            }
        }
        if (children.isEmpty()) {
            respond(exchange, 404, new byte[0]);
            return;
        }
        StringBuilder page = new StringBuilder("<html><body><h1>Index of " + path + "</h1><a href=\"../\">../</a>");
        for (String child : children) {
            page.append("<a href=\"").append(child).append("\">").append(child).append("</a>");
        }
        respond(exchange, 200, page.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    public void tearDown() {
        running.close();
        upstream.stop(0);
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty("jenesis.repository.block-private-import-hosts");
    }

    @Test
    public void a_plain_maven_tree_is_migrated_over_its_directory_listing() throws Exception {
        HttpResponse<String> submitted = post("/admin/import",
                "{\"source\":\"maven\",\"url\":\"" + url + "\",\"repository\":\"steady\"}");
        assertThat(submitted.statusCode()).as("accepted, runs in the background").isEqualTo(202);

        String completed = pollUntilTerminal(field(submitted.body(), "job"));
        assertThat(completed).contains("\"state\":\"completed\"").contains("\"imported\":2").contains("\"skipped\":0");

        assertThat(get("/maven/org/acme/one/1.0/one-1.0.jar").body())
                .isEqualTo("first jar".getBytes(StandardCharsets.UTF_8));
        assertThat(get("/maven/org/acme/two/1.0/two-1.0.pom").statusCode()).isEqualTo(200);
        assertThat(get("/maven/org/acme/one/1.0/one-1.0.jar.sha1").body())
                .as("the checksum sidecar is derived, not the imported copy")
                .isNotEqualTo("not imported".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void an_interrupted_walk_resumes_past_the_completed_subtree() throws Exception {
        HttpResponse<String> submitted = post("/admin/import",
                "{\"source\":\"maven\",\"url\":\"" + url + "\",\"repository\":\"flaky\"}");
        assertThat(submitted.statusCode()).isEqualTo(202);
        String job = field(submitted.body(), "job");

        String failed = pollUntilTerminal(job);
        assertThat(failed).contains("\"state\":\"failed\"").contains("\"imported\":1").contains("\"cursor\":\"tree:alpha/\"");
        assertThat(get("/maven/beta/b/1.0/b-1.0.jar").statusCode()).as("beta not yet imported").isEqualTo(404);

        HttpResponse<String> resumed = post("/admin/import", "{\"source\":\"maven\",\"url\":\"" + url
                + "\",\"repository\":\"flaky\",\"resume\":\"" + job + "\"}");
        assertThat(resumed.statusCode()).isEqualTo(202);
        assertThat(field(resumed.body(), "job")).as("the same job is continued").isEqualTo(job);

        String completed = pollUntilTerminal(job);
        assertThat(completed).contains("\"state\":\"completed\"").contains("\"imported\":2");
        assertThat(get("/maven/alpha/a/1.0/a-1.0.jar").statusCode()).isEqualTo(200);
        assertThat(get("/maven/beta/b/1.0/b-1.0.jar").statusCode()).isEqualTo(200);
    }

    @Test
    public void a_url_whose_host_does_not_answer_is_rejected_up_front() throws Exception {
        HttpResponse<String> submitted = post("/admin/import",
                "{\"source\":\"maven\",\"url\":\"http://unknown-host.invalid\",\"repository\":\"any\"}");
        assertThat(submitted.statusCode()).as("no async job for a host that cannot answer").isEqualTo(400);
    }

    private String pollUntilTerminal(String job) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            HttpResponse<String> status = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/import/" + job))
                    .GET().build(), BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            if (!status.body().contains("\"state\":\"running\"")) {
                return status.body();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("job " + job + " did not finish");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofByteArray());
    }

    private static String field(String json, String name) {
        String token = "\"" + name + "\":\"";
        int start = json.indexOf(token) + token.length();
        return json.substring(start, json.indexOf('"', start));
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
