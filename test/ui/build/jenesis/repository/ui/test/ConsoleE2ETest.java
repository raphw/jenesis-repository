package build.jenesis.repository.ui.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.ui.Application;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the Spring Boot console boots and serves. It publishes an artifact into a temporary filesystem
 * store (the backend the console module deliberately does not bundle), boots the real {@link Application} on an
 * ephemeral port under the {@code dev} security profile, and drives it over HTTP: the Actuator health endpoint is up,
 * the login page is served anonymously, an anonymous request to the console is redirected to login (deny-by-default),
 * and an authenticated user sees the browse panel rendering the store's real published contents.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsoleE2ETest {

    @TempDir
    private static Path store;

    private Application.Running running;
    private HttpClient client;
    private String base;
    private String blobHash;

    @BeforeAll
    public void boot() throws Exception {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("spring.profiles.active", "dev");
        // Publish an artifact so the browse has real contents to render.
        ArtifactStore backend = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? store.toString() : null);
        Publication publication = new Publication(backend);
        blobHash = publication.storeBlob(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        publication.link("/maven/org/example/demo/1/demo-1.jar", blobHash);

        running = Application.start(0);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        base = "http://localhost:" + running.port();
    }

    @AfterAll
    public void shutdown() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("spring.profiles.active");
    }

    @Test
    public void the_actuator_health_endpoint_reports_up() throws Exception {
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(base + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    public void the_login_page_is_served_anonymously() throws Exception {
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create(base + "/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("jenesis-repository");
        // The design-system stylesheet is linked into the shell head fragment, so every screen shares one base.
        assertThat(login.body()).contains("/css/app.css");
    }

    @Test
    public void the_design_system_stylesheet_is_served_with_its_tokens_and_components() throws Exception {
        // app.css is a static asset served anonymously (like pico.min.css), so the whole console shares one base.
        HttpResponse<String> css = client.send(
                HttpRequest.newBuilder(URI.create(base + "/css/app.css")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(css.statusCode()).isEqualTo(200);
        assertThat(css.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).contains("text/css"));
        // A design token, a component class and the dark-theme override are all present.
        assertThat(css.body()).contains("--app-space-1").contains(".app-badge").contains("[data-theme=\"dark\"]");
    }

    @Test
    public void the_console_redirects_an_anonymous_browser_request_to_login() throws Exception {
        HttpResponse<String> anonymous = client.send(
                HttpRequest.newBuilder(URI.create(base + "/"))
                        .header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(302);
        assertThat(anonymous.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).contains("/login"));
    }

    @Test
    public void an_authenticated_user_sees_the_browse_panel_linking_into_the_browser() throws Exception {
        HttpResponse<String> page = authGet("/console");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Browse");
        // The panel is now the entry point into the generic browse: it links to /browse and previews the published
        // namespace (the "maven" root of the published artifact) as a quick link.
        assertThat(page.body()).contains("/browse").contains("Published namespaces").contains("/browse?path=maven");
        // The console renders on the shared design-system base: the shell head links app.css and the page uses the
        // page-header component fragment from base.html.
        assertThat(page.body()).contains("/css/app.css");
        assertThat(page.body()).contains("app-page-header").contains("Repository console");
    }

    @Test
    public void the_browse_page_renders_a_breadcrumbed_tree_of_the_published_namespace() throws Exception {
        HttpResponse<String> page = authGet("/browse");
        assertThat(page.statusCode()).isEqualTo(200);
        // Rendered on the shared base: the breadcrumb component and the generic list/table.
        assertThat(page.body()).contains("app-breadcrumb").contains("Repository").contains("app-list");
        // The publish tree's single top-level entry is the "maven" folder, linking one level deeper.
        assertThat(page.body()).contains("/browse?path=maven").contains("maven/").contains("folder");
        // It never surfaces the content-addressed blobs bucket - a browse walks publish/, not blobs/.
        assertThat(page.body()).doesNotContain(blobHash);
    }

    @Test
    public void the_browse_page_drills_into_a_prefix_and_shows_the_artifact_leaf_with_its_size() throws Exception {
        HttpResponse<String> page = authGet("/browse?path=maven/org/example/demo/1");
        assertThat(page.statusCode()).isEqualTo(200);
        // The breadcrumb trail carries every navigated segment, and an up-link returns to the parent.
        assertThat(page.body()).contains("app-breadcrumb").contains("example").contains("/browse?path=maven/org/example");
        // The leaf is classified as an artifact (not a folder) and shows the stored blob size (3 bytes → "3 B").
        assertThat(page.body()).contains("demo-1.jar").contains("artifact").contains("3 B");
    }

    @Test
    public void a_folders_children_load_lazily_as_a_row_fragment() throws Exception {
        // The lazy-children endpoint the tree expands a folder with: it returns only the child rows under the prefix,
        // not the whole shell - proof that a level's children are fetched on demand, never a full-tree scan.
        HttpResponse<String> fragment = authGet("/browse/children?path=maven");
        assertThat(fragment.statusCode()).isEqualTo(200);
        assertThat(fragment.body()).contains("/browse?path=maven/org").contains("org/");
        // A fragment, not a page: no shell head/header markup.
        assertThat(fragment.body()).doesNotContain("<html").doesNotContain("app-page-header");
    }

    @Test
    public void the_browse_path_is_traversal_guarded_against_escaping_into_the_blobs_bucket() throws Exception {
        // A crafted "../blobs" must not walk up out of the publish/ subtree to enumerate the raw content-addressed
        // blobs (which would leak the stored hashes); the ".." segment is dropped, so no blob hash is ever exposed.
        HttpResponse<String> page = authGet("/browse?path=../blobs");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).doesNotContain(blobHash);
    }

    private HttpResponse<String> authGet(String path) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Basic " + credentials).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
