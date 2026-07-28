package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the free server ENFORCING ({@code auth=true}) with the strictly-opt-in anonymous role (WANON.1)
 * {@code jenesis.repository.anonymous-rights=repository:read} - the public-mirror pattern - and proves the choke-point:
 * a keyless read is served while a keyless write is still {@code 401}, the role is advertised on
 * {@code /api/capabilities}, and the governance advisory {@code jenesis.anonymous.enabled} is surfaced on
 * {@code /api/posture}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AnonymousAccessE2ETest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void boot() {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        // Enforcing (the secure default) plus a strictly-opt-in anonymous read grant: keys required for writes/admin,
        // but the keyless caller may read (the public mirror WRO.1 describes).
        System.setProperty("jenesis.repository.auth", "true");
        System.setProperty("jenesis.repository.anonymous-rights", "repository:read");
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
        System.clearProperty("jenesis.repository.anonymous-rights");
    }

    private int status(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    public void a_keyless_read_is_served_against_the_anonymous_grant() throws Exception {
        // A keyless GET is authorized by the anonymous repository:read grant and reaches the store - an absent artifact
        // is a normal 404, NOT the 401 an enforcing deployment gives a keyless request with no anonymous role.
        int status = status(HttpRequest.newBuilder(URI.create(base + "/repository/maven/org/x/a/1/a-1.jar"))
                .GET().build());
        assertThat(status).as("a keyless read reaches the store (404 miss), not 401").isEqualTo(404);
    }

    @Test
    public void a_keyless_write_is_still_rejected() throws Exception {
        int status = status(HttpRequest.newBuilder(URI.create(base + "/repository/maven/org/x/a/1/a-1.jar"))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[64])).build());
        assertThat(status).as("a keyless write is not covered by anonymous read - 401").isEqualTo(401);
    }

    @Test
    public void the_anonymous_role_is_advertised_on_capabilities() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/capabilities")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"anonymousRights\":\"repository:read\"");
    }

    @Test
    public void the_anonymous_role_raises_a_posture_advisory() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/posture")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jenesis.anonymous.enabled");
    }
}
