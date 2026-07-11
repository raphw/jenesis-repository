package build.jenesis.repository.bundle.test;

import module java.base;
import module java.net.http;
import build.jenesis.repository.bundle.AllInOne;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the all-in-one composition (every free SPI implementation on the module path, through the image's own
 * {@code AllInOne} launcher) and proves the config-driven trim over raw HTTP: with nothing configured every
 * capability answers; an explicit {@code jenesis.repository.<feature>=false} degrades that implementation exactly
 * like a missing module; an exclusive {@code jenesis.repository.<spi>=<feature>} selection is honoured, and one
 * naming an uninstalled implementation degrades to the documented {@code 501} rather than failing the boot. Each
 * boot uses a fresh store root, and the toggles ride system properties - the same environment surface
 * ({@code JENESIS_REPOSITORY_*} through relaxed binding) that trims the Docker image.
 */
public class AllInOneToggleE2ETest {

    @TempDir
    private Path root;

    private final HttpClient client = HttpClient.newHttpClient();

    private final List<String> properties = new ArrayList<>();

    @AfterEach
    public void cleanUp() {
        properties.forEach(System::clearProperty);
        System.clearProperty("JENESIS_STORE_ROOT");
    }

    private AllInOne.Running boot(Map<String, String> configuration) {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        configuration.forEach((key, value) -> {
            properties.add(key);
            System.setProperty(key, value);
        });
        return AllInOne.start(0);
    }

    @Test
    public void every_capability_answers_until_configured_off() throws Exception {
        try (AllInOne.Running server = boot(Map.of())) {
            assertThat(get(server, "/actuator/health").statusCode()).isEqualTo(200);
            assertThat(get(server, "/actuator/health").body()).contains("\"status\":\"UP\"");
            String path = "/repository/maven/org/example/lib/1.0/lib-1.0.pom";
            assertThat(put(server, path, pom()).statusCode()).as("the maven layout claims its path").isEqualTo(201);
            assertThat(get(server, path).statusCode()).as("and serves it back").isEqualTo(200);
            assertThat(put(server, "/repository/raw/some/file.bin", "raw".getBytes(StandardCharsets.UTF_8))
                    .statusCode()).as("the raw layout claims its path").isEqualTo(201);
        }
    }

    @Test
    public void a_disabled_format_degrades_exactly_like_a_missing_module() throws Exception {
        try (AllInOne.Running server = boot(Map.of("jenesis.repository.maven", "false"))) {
            String path = "/repository/maven/org/example/lib/1.0/lib-1.0.pom";
            assertThat(put(server, path, pom()).statusCode()).as("the disabled layout claims nothing").isEqualTo(404);
            assertThat(put(server, "/repository/raw/some/file.bin", "raw".getBytes(StandardCharsets.UTF_8))
                    .statusCode()).as("a sibling layout is untouched").isEqualTo(201);
        }
    }

    @Test
    public void a_disabled_fetcher_answers_the_documented_501_and_a_selection_is_honoured() throws Exception {
        // The import trigger consults the fetcher before it validates the request, so an empty POST cleanly
        // separates the two states without starting a job or touching any upstream: with the http fetcher enabled
        // the endpoint answers its 400 (url and repository are required); configured off - or deselected by an
        // explicit jenesis.repository.fetcher naming an uninstalled implementation - it answers the documented 501.
        String trigger = "/repository/admin/import";
        try (AllInOne.Running server = boot(Map.of())) {
            assertThat(post(server, trigger).statusCode()).as("fetcher installed: the endpoint answers")
                    .isEqualTo(400);
        }
        try (AllInOne.Running server = boot(Map.of("jenesis.repository.http", "false"))) {
            assertThat(post(server, trigger).statusCode()).as("fetcher configured off: the documented 501")
                    .isEqualTo(501);
        }
        try (AllInOne.Running server = boot(Map.of("jenesis.repository.fetcher", "phantom"))) {
            assertThat(post(server, trigger).statusCode()).as("a selection naming an uninstalled fetcher degrades")
                    .isEqualTo(501);
        }
        try (AllInOne.Running server = boot(Map.of("jenesis.repository.fetcher", "http"))) {
            assertThat(post(server, trigger).statusCode()).as("an explicit selection of the installed fetcher answers")
                    .isEqualTo(400);
        }
    }

    private HttpResponse<String> post(AllInOne.Running server, String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(AllInOne.Running server, String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(AllInOne.Running server, String path, byte[] body)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] pom() {
        return "<project><modelVersion>4.0.0</modelVersion></project>".getBytes(StandardCharsets.UTF_8);
    }
}
