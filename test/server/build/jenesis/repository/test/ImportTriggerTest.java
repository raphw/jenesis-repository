package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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

    private WireMockServer nexus;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private String upstream;

    @BeforeAll
    public void setUp() throws IOException {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());

        nexus = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        nexus.start();
        upstream = "http://localhost:" + nexus.port();

        Map<String, byte[]> assets = new HashMap<>();
        assets.put("/repository/releases/org/acme/one/1.0/one-1.0.jar", "first jar".getBytes(StandardCharsets.UTF_8));
        assets.put("/repository/releases/org/acme/two/1.0/two-1.0.jar", "second jar".getBytes(StandardCharsets.UTF_8));
        String pageOne = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/acme/one/1.0/one-1.0.jar\","
                + "\"downloadUrl\":\"" + upstream + "/repository/releases/org/acme/one/1.0/one-1.0.jar\"}]}],"
                + "\"continuationToken\":\"page2\"}";
        String pageTwo = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/acme/two/1.0/two-1.0.jar\","
                + "\"downloadUrl\":\"" + upstream + "/repository/releases/org/acme/two/1.0/two-1.0.jar\"}]}],"
                + "\"continuationToken\":null}";

        assets.forEach((path, body) ->
                nexus.stubFor(any(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withBody(body))));
        // Page one always lists first. Page two 500s the first time it is fetched, then serves - a WireMock Scenario
        // reproducing the original one-shot failure the resume must survive. The token-bearing page-two stubs outrank
        // the repository-only page-one stub, so they win once the continuation token is present.
        nexus.stubFor(any(urlPathEqualTo("/service/rest/v1/components")).atPriority(5)
                .withQueryParam("repository", equalTo("releases"))
                .willReturn(aResponse().withStatus(200).withBody(pageOne.getBytes(StandardCharsets.UTF_8))));
        nexus.stubFor(any(urlPathEqualTo("/service/rest/v1/components")).atPriority(1)
                .withQueryParam("continuationToken", equalTo("page2"))
                .inScenario("page2-flake").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("page2-retried"));
        nexus.stubFor(any(urlPathEqualTo("/service/rest/v1/components")).atPriority(1)
                .withQueryParam("continuationToken", equalTo("page2"))
                .inScenario("page2-flake").whenScenarioStateIs("page2-retried")
                .willReturn(aResponse().withStatus(200).withBody(pageTwo.getBytes(StandardCharsets.UTF_8))));

        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        // The import SSRF screen now blocks a loopback upstream by default; this test's fake Nexus is on localhost, so
        // pin the internal-host opt-out to preserve its intent (the guard itself is proven by ImportHostGuardTest).
        System.setProperty("jenesis.repository.block-private-import-hosts", "false");
        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void tearDown() {
        running.close();
        nexus.stop();
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty("jenesis.repository.block-private-import-hosts");
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
}
