package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import trigger's SSRF screen: with the anonymous-possible default an unguarded import URL would let an
 * unauthenticated caller aim the server at its own network - a cloud metadata service (169.254.169.254), the loopback
 * control plane (127.0.0.1) or an internal host. The screen is on by default, so a loopback upstream is refused with a
 * {@code 400}; an internal-host migration is an explicit opt-out
 * ({@code jenesis.repository.block-private-import-hosts=false}), and the same loopback import then runs. A fake Nexus
 * on localhost stands in for the private host both cases target.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ImportHostGuardTest {

    private static final String GUARD = "jenesis.repository.block-private-import-hosts";

    @TempDir
    static Path root;

    private HttpServer nexus;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private String upstream;

    @BeforeAll
    public void setUp() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        System.setProperty("jenesis.repository.auth", "false");

        nexus = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream = "http://localhost:" + nexus.getAddress().getPort();
        // An empty first page: a migration that opts past the guard starts, walks nothing and completes cleanly.
        byte[] page = "{\"items\":[],\"continuationToken\":null}".getBytes(StandardCharsets.UTF_8);
        nexus.createContext("/service/rest/v1/components", exchange -> respond(exchange, page));
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
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty(GUARD);
    }

    @Test
    public void a_loopback_import_is_refused_by_default() throws Exception {
        System.setProperty(GUARD, "true");                     // the shipped default; pinned to be explicit
        HttpResponse<String> refused = post("{\"source\":\"nexus\",\"url\":\"" + upstream
                + "\",\"repository\":\"releases\"}");
        assertThat(refused.statusCode()).as("a loopback upstream is an SSRF vector, refused up front").isEqualTo(400);
        assertThat(refused.body()).contains("public host");
    }

    @Test
    public void the_opt_out_allows_an_internal_host_migration() throws Exception {
        System.setProperty(GUARD, "false");                    // explicit internal-host opt-out
        HttpResponse<String> accepted = post("{\"source\":\"nexus\",\"url\":\"" + upstream
                + "\",\"repository\":\"releases\"}");
        assertThat(accepted.statusCode()).as("the opt-out lets the same loopback import run").isEqualTo(202);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/admin/import"))
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
