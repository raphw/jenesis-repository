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

    @BeforeAll
    public void boot() throws Exception {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("spring.profiles.active", "dev");
        // Publish an artifact so the browse panel has real contents to render.
        ArtifactStore backend = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? store.toString() : null);
        Publication publication = new Publication(backend);
        String hash = publication.storeBlob(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        publication.link("/maven/org/example/demo/1/demo-1.jar", hash);

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
    public void an_authenticated_user_sees_the_browse_panel_with_real_data() throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create(base + "/console"))
                        .header("Authorization", "Basic " + credentials).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Browse");
        assertThat(page.body()).contains("Published paths");
        // The console renders on the shared design-system base: the shell head links app.css and the page uses the
        // page-header component fragment from base.html.
        assertThat(page.body()).contains("/css/app.css");
        assertThat(page.body()).contains("app-page-header").contains("Repository console");
    }
}
