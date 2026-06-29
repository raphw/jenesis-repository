package build.jenesis.repository.test;

import build.jenesis.repository.Authorization;
import build.jenesis.repository.RepositoryServer;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Right;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the credential model gates the wire. An enforcing {@link RepositoryServer} answers 401
 * without a key, 403 for a key that lacks the required right, and 201/200 for a key carrying
 * {@code repository:write} / {@code repository:read} - the rights travelling in the {@code Jenesis-Repository-Key}
 * header, off the same grants the unit test exercises in isolation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryAuthE2ETest {

    @TempDir
    private static Path store;

    private RepositoryServer.Running server;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void boot() throws IOException {
        ArtifactStore backend = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? store.toString() : null);
        Authorization authorization = Authorization.enforcing(backend);
        authorization.grant("acme.ci", "*", Right.REPOSITORY_READ, Right.REPOSITORY_WRITE);
        authorization.grant("acme.ro", "*", Right.REPOSITORY_READ);
        server = new RepositoryServer(backend).withAuthorization(authorization).start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port() + "/";
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void a_deploy_without_a_key_is_unauthorized() throws Exception {
        assertThat(put("maven/org/example/a/1/a-1.jar", null).statusCode()).isEqualTo(401);
    }

    @Test
    public void a_read_only_key_may_not_deploy() throws Exception {
        assertThat(put("maven/org/example/b/1/b-1.jar", "acme.ro").statusCode()).isEqualTo(403);
    }

    @Test
    public void a_deploy_key_deploys_and_a_read_key_reads() throws Exception {
        assertThat(put("maven/org/example/c/1/c-1.jar", "acme.ci").statusCode()).isEqualTo(201);
        assertThat(get("maven/org/example/c/1/c-1.jar", "acme.ro").statusCode()).isEqualTo(200);
    }

    @Test
    public void an_unknown_key_is_forbidden() throws Exception {
        assertThat(get("maven/org/example/a/1/a-1.jar", "acme.bogus").statusCode()).isEqualTo(403);
    }

    private HttpResponse<byte[]> put(String path, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}));
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String path, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (key != null) {
            request.header("Jenesis-Repository-Key", key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
