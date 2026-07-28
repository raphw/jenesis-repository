package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WFE.1 - the free import edge yields when a distribution owns it, end-to-end. The real free server boots with
 * {@link TestImportEdgeProvider} activated (its required-config key set), so the server discovers an installed
 * {@link build.jenesis.repository.server.ImportEdgeProvider} via {@link java.util.ServiceLoader} exactly as a richer
 * distribution would. The {@link build.jenesis.repository.server.RepositoryAutoConfiguration} then does not register the
 * free {@code ImportEdgeController}, so its {@code /repository/admin/import} mapping never joins the handler mapping and
 * the repo-less import edge is no longer served - the request falls through to the format catch-all and is a
 * {@code 404}, leaving a distribution's own (tenant-scoped) import controller the only import edge. No
 * {@code WebMvcRegistrations} mapping-suppression bean is involved.
 *
 * <p>The complementary "served unchanged when no provider is installed" half is proven by the rest of the import suite
 * ({@link ImportTriggerTest}, {@link ImportJobsStatusTest}), which boot the same server with the provider inert and see
 * the free edge answer {@code 202} / {@code 405} / {@code 200} exactly as before.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ImportEdgeYieldTest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void boot() {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("jenesis.repository.auth", "false");
        // Activate the discovered test ImportEdgeProvider, so the free ImportEdgeController is not registered.
        System.setProperty(TestImportEdgeProvider.ACTIVATION_KEY, "true");
        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port();
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty(TestImportEdgeProvider.ACTIVATION_KEY);
    }

    @Test
    public void a_post_to_the_repo_less_import_edge_is_not_served_when_a_provider_is_installed() throws Exception {
        // A well-formed import trigger that would be a 202 (or a 400 validation) on the free edge is instead a 404:
        // the free mapping is not registered, so the request falls through to the format catch-all as an unclaimed path.
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/repository/admin/import"))
                        .POST(BodyPublishers.ofString("{\"source\":\"nexus\",\"url\":\"https://example.test\","
                                + "\"repository\":\"releases\"}"))
                        .build(),
                BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("the free import edge yielded - its mapping is not registered, so this is an unclaimed path")
                .isEqualTo(404);
    }

    @Test
    public void a_get_status_on_the_repo_less_import_edge_is_not_served_when_a_provider_is_installed() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/repository/admin/import/any-job-id")).GET().build(),
                BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("the free status edge yielded too - no free import mapping is registered at all")
                .isEqualTo(404);
    }

    @Test
    public void the_rest_of_the_free_server_is_unaffected_by_the_yielded_import_edge() throws Exception {
        // Only the import edge yields; every other free surface is served exactly as before.
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/capabilities")).GET().build(),
                BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"readOnly\":").contains("\"auth\":");
    }
}
