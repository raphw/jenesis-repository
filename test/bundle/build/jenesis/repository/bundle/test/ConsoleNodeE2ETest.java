package build.jenesis.repository.bundle.test;

import build.jenesis.repository.ui.Application;
import module java.base;
import module java.net.http;
import module org.junit.jupiter.api;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the console node of the all-in-one image - the {@link build.jenesis.repository.bundle.Console} launcher, which
 * runs the console {@link Application} off the bundle's own {@code allinone-console.properties} (the config the image
 * loads with {@code spring.config.name=allinone-console}) instead of the ambiguous root {@code application.properties}.
 * The AllInOne server-node test proves the server composition; this proves the console node the same image also runs:
 * over a fresh filesystem store, under the production security chain (no {@code dev} profile), the login page serves
 * anonymously and the console denies an anonymous request - deny-by-default, exactly like the server node's key gate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsoleNodeE2ETest {

    @TempDir
    private static Path store;

    private ConfigurableApplicationContext context;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void boot() {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        // The exact boot the Console launcher performs: the console Application off allinone-console.properties. The
        // port rides as an argument (config files outrank default properties, so a property-passed 0 would bind 8081).
        context = new SpringApplicationBuilder(Application.class)
                .properties("spring.config.name=allinone-console")
                .run("--server.port=0");
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        base = "http://localhost:" + port;
    }

    @AfterAll
    public void shutdown() {
        if (context != null) {
            context.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    @Test
    public void the_console_node_reports_health_up() throws Exception {
        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    public void the_login_page_is_served_anonymously() throws Exception {
        HttpResponse<String> login = get("/login");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("jenesis-repository");
    }

    @Test
    public void the_console_denies_an_anonymous_request() throws Exception {
        HttpResponse<String> console = client.send(
                HttpRequest.newBuilder(URI.create(base + "/console")).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(console.statusCode()).as("deny-by-default: an anonymous console request is bounced").isEqualTo(302);
        assertThat(console.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).contains("/login"));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
