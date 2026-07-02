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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the asynchronous admin migration trigger over HTTP, including a resume. A fake Nexus (a JDK HTTP server)
 * holds a two-page Maven repository whose second page fails the first time it is listed. A {@code POST /admin/import}
 * returns at once with a job id; polling {@code GET /admin/import/<id>} shows the job fail after the first page,
 * with a continuation cursor recorded. A second {@code POST} naming that job resumes the walk from the cursor and
 * its counts, completes, and both jars are then served - so the trigger runs in the background and a migration
 * survives an interruption.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ImportTriggerTest {

    @TempDir
    static Path root;

    private HttpServer nexus;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private String upstream;
    private final AtomicBoolean secondPageFailedOnce = new AtomicBoolean();

    @BeforeAll
    public void setUp() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());

        nexus = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream = "http://localhost:" + nexus.getAddress().getPort();

        Map<String, byte[]> assets = new HashMap<>();
        assets.put("/repository/releases/org/acme/one/1.0/one-1.0.jar", "first jar".getBytes(StandardCharsets.UTF_8));
        assets.put("/repository/releases/org/acme/two/1.0/two-1.0.jar", "second jar".getBytes(StandardCharsets.UTF_8));
        String pageOne = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/acme/one/1.0/one-1.0.jar\","
                + "\"downloadUrl\":\"" + upstream + "/repository/releases/org/acme/one/1.0/one-1.0.jar\"}]}],"
                + "\"continuationToken\":\"page2\"}";
        String pageTwo = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/acme/two/1.0/two-1.0.jar\","
                + "\"downloadUrl\":\"" + upstream + "/repository/releases/org/acme/two/1.0/two-1.0.jar\"}]}],"
                + "\"continuationToken\":null}";

        nexus.createContext("/repository", exchange -> {
            byte[] body = assets.get(exchange.getRequestURI().getPath());
            respond(exchange, body == null ? 404 : 200, body == null ? new byte[0] : body);
        });
        nexus.createContext("/service/rest/v1/components", exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (!"page2".equals(query.get("continuationToken"))) {
                respond(exchange, 200, pageOne.getBytes(StandardCharsets.UTF_8));
            } else if (secondPageFailedOnce.compareAndSet(false, true)) {
                respond(exchange, 500, new byte[0]);
            } else {
                respond(exchange, 200, pageTwo.getBytes(StandardCharsets.UTF_8));
            }
        });
        nexus.start();

        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void tearDown() {
        running.close();
        nexus.stop(0);
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void a_migration_runs_asynchronously_and_resumes_after_a_failure() throws Exception {
        String body = "{\"source\":\"nexus\",\"url\":\"" + upstream + "\",\"repository\":\"releases\"}";
        HttpResponse<String> submitted = post("/admin/import", body);
        assertThat(submitted.statusCode()).as("accepted, runs in the background").isEqualTo(202);
        String job = field(submitted.body(), "job");

        String failed = pollUntilTerminal(job);
        assertThat(failed).contains("\"state\":\"failed\"").contains("\"imported\":1").contains("\"cursor\":\"page2\"");
        assertThat(get("/maven/org/acme/two/1.0/two-1.0.jar").statusCode()).as("second jar not yet imported").isEqualTo(404);

        HttpResponse<String> resumed = post("/admin/import", "{\"source\":\"nexus\",\"url\":\"" + upstream
                + "\",\"repository\":\"releases\",\"resume\":\"" + job + "\"}");
        assertThat(resumed.statusCode()).isEqualTo(202);
        assertThat(field(resumed.body(), "job")).as("the same job is continued").isEqualTo(job);

        String completed = pollUntilTerminal(job);
        assertThat(completed).contains("\"state\":\"completed\"").contains("\"imported\":2");

        assertThat(get("/maven/org/acme/one/1.0/one-1.0.jar").statusCode()).isEqualTo(200);
        assertThat(get("/maven/org/acme/two/1.0/two-1.0.jar").statusCode()).isEqualTo(200);
    }

    @Test
    public void a_get_without_a_job_id_is_rejected() throws Exception {
        HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/import"))
                .GET().build(), BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(405);
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
}
