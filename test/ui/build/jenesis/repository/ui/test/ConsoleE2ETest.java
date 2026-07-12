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
    private String quarantineHash;

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
        // A gate-held artifact lands under publish/quarantine/... - withheld from a GET and skipped by the export; it
        // is set up here so the browse-hides-quarantine test has a real held artifact to prove is not disclosed.
        quarantineHash = publication.storeBlob(new ByteArrayInputStream(new byte[]{9, 9, 9, 9}));
        publication.link("/quarantine/maven/org/secret/held/1/held-1.jar", quarantineHash);

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
    public void the_theme_switch_script_is_served_and_wired_into_the_shell() throws Exception {
        // /js/theme.js ships next to app.css: it applies a stored light/dark choice before first paint (loaded
        // without defer by the shell head) and wires every [data-theme-select] control the nav carries.
        HttpResponse<String> script = client.send(
                HttpRequest.newBuilder(URI.create(base + "/js/theme.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.body()).contains("data-theme-select").contains("jenesis-theme").contains("data-theme");
        HttpResponse<String> console = authGet("/console");
        assertThat(console.body()).as("the head loads the theme script and the nav carries the switch")
                .contains("/js/theme.js").contains("data-theme-select").contains("aria-label=\"Color theme\"");
    }

    @Test
    public void the_empty_state_renders_only_when_a_screen_is_actually_empty() throws Exception {
        // Regression for the th:if/th:replace precedence trap: fragment inclusion (100) runs before the conditional
        // (300), so `th:if` on the replaced element itself is ignored and the empty state renders always. The fix
        // wraps the inclusion in a th:block - a populated console/browse must NOT show its empty message.
        assertThat(authGet("/console").body()).doesNotContain("No panels are registered.");
        assertThat(authGet("/browse").body()).doesNotContain("The repository is empty.");
        // An actually-empty folder still gets the treatment, never a blank screen: the traversal-guarded ../blobs
        // prefix lists nothing, so the shared empty component renders there.
        assertThat(authGet("/browse?path=nowhere").body()).contains("app-empty").contains("This folder is empty.");
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

    @Test
    public void the_browse_hides_the_quarantine_review_subtree_a_get_does_not_serve() throws Exception {
        // A gate-held artifact lives at publish/quarantine/...; a plain GET 404s it and the /assets export never walks
        // it, so the interactive browse must not surface it either. The root listing shows no quarantine folder, the
        // browse panel offers no quarantine quick link, and a crafted ?path=quarantine/... cannot navigate in - so the
        // browse never discloses the paths or sizes of withheld artifacts (which a GET would not).
        HttpResponse<String> root = authGet("/browse");
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(root.body()).doesNotContain("/browse?path=quarantine").doesNotContain("quarantine/");
        // The panel's published-namespace quick links exclude the review subtree too.
        assertThat(authGet("/console").body()).doesNotContain("/browse?path=quarantine");
        // A crafted navigation into the subtree drops the leading "quarantine" segment (browsing the empty live path
        // instead), so the held artifact's leaf, path and size are never rendered.
        HttpResponse<String> into = authGet("/browse?path=quarantine/maven/org/secret/held/1");
        assertThat(into.statusCode()).isEqualTo(200);
        assertThat(into.body()).doesNotContain("held-1.jar").doesNotContain(quarantineHash);
        // And the export still omits it, unchanged - the browse now matches that contract.
        assertThat(authGet("/assets").body()).doesNotContain("held-1.jar").doesNotContain(quarantineHash);
    }

    @Test
    public void the_asset_listing_downloads_as_ndjson_of_the_published_pointers() throws Exception {
        // The console face of GET /api/assets: a streamed, downloadable export of every published asset. One NDJSON
        // line per pointer - the request path, the stored size (3 bytes) and the content address (the blob's SHA-256,
        // its checksum, not a secret) - read straight from the publish/ pointer tree, never an artifact blob.
        HttpResponse<String> assets = authGet("/assets");
        assertThat(assets.statusCode()).isEqualTo(200);
        assertThat(assets.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                disposition -> assertThat(disposition).contains("attachment").contains("assets.ndjson"));
        assertThat(assets.body()).contains("\"path\":\"/maven/org/example/demo/1/demo-1.jar\"")
                .contains("\"size\":3").contains("\"sha256\":\"" + blobHash + "\"");
        // The content-addressed blobs bucket is never walked, so no raw blobs/ key ever leaks as an exported path.
        assertThat(assets.body()).doesNotContain("\"path\":\"/blobs");
        // The browse page links the download so a console user can reach it.
        assertThat(authGet("/browse").body()).contains("/assets").contains("Download asset listing");
    }

    @Test
    public void the_asset_listing_is_denied_to_an_anonymous_request() throws Exception {
        // Deny-by-default: the export is a GET caught by anyRequest().authenticated(), so an anonymous browser request
        // is bounced to login rather than handed the store's contents.
        HttpResponse<String> anonymous = client.send(
                HttpRequest.newBuilder(URI.create(base + "/assets"))
                        .header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(302);
        assertThat(anonymous.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).contains("/login"));
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
