package build.jenesis.repository.test;

import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the additive Spring Boot front ({@link RepositoryApplication}) serves the same dual-layout
 * repository as the JDK skeleton, exposes the Actuator health endpoint, and gates the wire with the same
 * {@link Authorization} credential model. It boots the real Spring server on an ephemeral port over a temporary
 * filesystem store and drives it over HTTP: a Maven artifact round-trips through a {@code PUT} then {@code GET}; the
 * Actuator health endpoint reports {@code UP}; and, with authorization enforced, an unkeyed request is rejected
 * while a key carrying {@code repository:write}/{@code repository:read} deploys and reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositorySpringE2ETest {

    @TempDir
    private static Path anonymousStore;

    @TempDir
    private static Path enforcingStore;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void boot() {
        System.setProperty("JENESIS_STORE_ROOT", anonymousStore.toString());
        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port() + "/";
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void a_maven_artifact_round_trips_over_http() throws Exception {
        byte[] body = {1, 2, 3, 4};
        HttpResponse<byte[]> put = client.send(
                HttpRequest.newBuilder(URI.create(base + "maven/org/example/spring/1/spring-1.jar"))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(put.statusCode()).isEqualTo(201);
        HttpResponse<byte[]> get = client.send(
                HttpRequest.newBuilder(URI.create(base + "maven/org/example/spring/1/spring-1.jar")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(body);
    }

    @Test
    public void the_actuator_health_endpoint_reports_up() throws Exception {
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(base + "actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @Test
    public void enforcing_auth_rejects_an_unkeyed_request_and_admits_a_keyed_one() throws Exception {
        System.setProperty("JENESIS_STORE_ROOT", enforcingStore.toString());
        System.setProperty("jenesis.repository.auth", "true");
        RepositoryApplication.Running enforcing = null;
        try {
            enforcing = RepositoryApplication.start(0);
            ArtifactStore backend = ArtifactStoreProvider.resolve(
                    "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? enforcingStore.toString() : null);
            Authorization authorization = Authorization.enforcing(backend);
            String ci = Authorization.mint("acme");
            authorization.grant(ci, "*", Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE);
            String enforcingBase = "http://localhost:" + enforcing.port() + "/";

            HttpResponse<byte[]> unkeyed = client.send(
                    HttpRequest.newBuilder(URI.create(enforcingBase + "maven/org/example/auth/1/auth-1.jar"))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{9})).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(unkeyed.statusCode()).isIn(401, 403);

            HttpResponse<byte[]> keyedPut = client.send(
                    HttpRequest.newBuilder(URI.create(enforcingBase + "maven/org/example/auth/1/auth-1.jar"))
                            .header("Jenesis-Repository-Key", ci)
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{9})).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(keyedPut.statusCode()).isEqualTo(201);

            HttpResponse<byte[]> keyedGet = client.send(
                    HttpRequest.newBuilder(URI.create(enforcingBase + "maven/org/example/auth/1/auth-1.jar"))
                            .header("Jenesis-Repository-Key", ci).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(keyedGet.statusCode()).isEqualTo(200);
        } finally {
            if (enforcing != null) {
                enforcing.close();
            }
            System.clearProperty("jenesis.repository.auth");
            System.setProperty("JENESIS_STORE_ROOT", anonymousStore.toString());
        }
    }
}
