package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the vendor-neutral {@code maven} migration over HTTP against a WireMock upstream serving a Maven tree with
 * generated autoindex pages - no Nexus, no Artifactory, no vendor API: the proof that any server exposing the Maven
 * layout is a migration source. A {@code POST /admin/import} walks the directory listing in the background and the
 * artifacts are then served, with metadata and checksum sidecars left behind; a walk whose subtree listing fails once
 * records a {@code tree:} cursor and a second {@code POST} naming the job resumes past the completed subtree; and a URL
 * whose host does not answer at all is rejected up front with a {@code 400}. The autoindex generation and the one-shot
 * subtree failure are a WireMock response transformer over the seeded file set - the "any Maven-layout server"
 * behaviour the hand-rolled stub expressed, without a {@code jdk.httpserver}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MavenTreeImportTest {

    @TempDir
    static Path root;

    private WireMockServer upstream;
    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;
    private String url;
    private final Map<String, byte[]> files = new HashMap<>();
    private final AtomicBoolean betaFailedOnce = new AtomicBoolean();

    @BeforeAll
    public void setUp() {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());

        files.put("/steady/org/acme/one/1.0/one-1.0.jar", "first jar".getBytes(StandardCharsets.UTF_8));
        files.put("/steady/org/acme/one/1.0/one-1.0.jar.sha1", "not imported".getBytes(StandardCharsets.UTF_8));
        files.put("/steady/org/acme/one/maven-metadata.xml", "<metadata/>".getBytes(StandardCharsets.UTF_8));
        files.put("/steady/org/acme/two/1.0/two-1.0.pom", "<project/>".getBytes(StandardCharsets.UTF_8));
        files.put("/flaky/alpha/a/1.0/a-1.0.jar", "alpha jar".getBytes(StandardCharsets.UTF_8));
        files.put("/flaky/beta/b/1.0/b-1.0.jar", "beta jar".getBytes(StandardCharsets.UTF_8));

        upstream = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort()
                .extensions(new MavenTree(files, betaFailedOnce)));
        upstream.start();
        upstream.stubFor(any(anyUrl()).willReturn(aResponse()));
        url = "http://localhost:" + upstream.port();

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

    @AfterAll
    public void tearDown() {
        running.close();
        upstream.stop();
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

    /**
     * The "any Maven-layout server" upstream: a seeded file served at its exact path, an nginx-style autoindex page
     * generated for a directory path, a one-shot {@code 500} for the {@code /flaky/beta/} subtree (so a walk fails once
     * and resumes), and a {@code 404} otherwise - a 1:1 port of the former hand-rolled handler.
     */
    private static final class MavenTree implements ResponseDefinitionTransformerV2 {

        private final Map<String, byte[]> files;
        private final AtomicBoolean betaFailedOnce;

        private MavenTree(Map<String, byte[]> files, AtomicBoolean betaFailedOnce) {
            this.files = files;
            this.betaFailedOnce = betaFailedOnce;
        }

        @Override
        public String getName() {
            return "maven-tree";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            String requestUrl = event.getRequest().getUrl();
            String path = requestUrl.indexOf('?') < 0 ? requestUrl : requestUrl.substring(0, requestUrl.indexOf('?'));
            byte[] file = files.get(path);
            if (file != null) {
                return aResponse().withStatus(200).withBody(file).build();
            }
            if (path.endsWith("/")) {
                if (path.equals("/flaky/beta/") && betaFailedOnce.compareAndSet(false, true)) {
                    return aResponse().withStatus(500).build();
                }
                return listing(path);
            }
            return aResponse().withStatus(404).build();
        }

        /** A plain autoindex page: the direct children of the directory, each a relative link, as nginx would render. */
        private ResponseDefinition listing(String path) {
            TreeSet<String> children = new TreeSet<>();
            for (String key : files.keySet()) {
                if (key.startsWith(path)) {
                    String rest = key.substring(path.length());
                    int slash = rest.indexOf('/');
                    children.add(slash < 0 ? rest : rest.substring(0, slash + 1));
                }
            }
            if (children.isEmpty()) {
                return aResponse().withStatus(404).build();
            }
            StringBuilder page = new StringBuilder("<html><body><h1>Index of " + path + "</h1><a href=\"../\">../</a>");
            for (String child : children) {
                page.append("<a href=\"").append(child).append("\">").append(child).append("</a>");
            }
            return aResponse().withStatus(200)
                    .withBody(page.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8)).build();
        }
    }
}
