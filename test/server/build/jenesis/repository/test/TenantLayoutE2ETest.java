package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Proves the shared {@code <tenant>/<repository>/...} store layout behind {@link RepositoryRouting}: the free
 * deployment is the fixed-tenant specialization, so everything a publish persists lands inside the configured
 * artifact space - {@code default/default/} out of the box, or wherever {@code jenesis.repository.tenant} /
 * {@code jenesis.repository.repository} point - and nothing is written at the store root outside it. This is what
 * makes single- and multi-tenant two configurations over one layout: a multi-tenant routing addressing the same
 * doubly-scoped space finds the data where the fixed-tenant deployment left it. The route contract itself is
 * hardened alongside: a {@link RepositoryRouting.Route} never carries a null tenant, repository, store or path.
 */
public class TenantLayoutE2ETest {

    @TempDir
    private static Path defaultSpaceRoot;

    @TempDir
    private static Path configuredSpaceRoot;

    @TempDir
    private static Path routeStoreRoot;

    @Test
    public void a_publish_lands_in_the_default_default_artifact_space() throws Exception {
        System.setProperty("JENESIS_STORE_ROOT", defaultSpaceRoot.toString());
        RepositoryApplication.Running server = null;
        try {
            server = RepositoryApplication.start(0);
            roundTrip("http://localhost:" + server.port() + "/repository/maven/org/example/layout/1/layout-1.jar");
            Path space = defaultSpaceRoot.resolve("default").resolve("default");
            assertThat(space).isDirectory();
            try (Stream<Path> stored = Files.walk(space)) {
                assertThat(stored.filter(Files::isRegularFile)).isNotEmpty();
            }
            try (Stream<Path> top = Files.list(defaultSpaceRoot)) {
                assertThat(top.map(path -> path.getFileName().toString())).containsExactly("default");
            }
        } finally {
            if (server != null) {
                server.close();
            }
            System.clearProperty("JENESIS_STORE_ROOT");
        }
    }

    @Test
    public void the_configured_tenant_and_repository_relocate_the_artifact_space() throws Exception {
        System.setProperty("JENESIS_STORE_ROOT", configuredSpaceRoot.toString());
        System.setProperty("jenesis.repository.tenant", "acme");
        System.setProperty("jenesis.repository.repository", "main");
        RepositoryApplication.Running server = null;
        try {
            server = RepositoryApplication.start(0);
            roundTrip("http://localhost:" + server.port() + "/repository/maven/org/example/layout/1/layout-1.jar");
            Path space = configuredSpaceRoot.resolve("acme").resolve("main");
            assertThat(space).isDirectory();
            try (Stream<Path> stored = Files.walk(space)) {
                assertThat(stored.filter(Files::isRegularFile)).isNotEmpty();
            }
            try (Stream<Path> top = Files.list(configuredSpaceRoot)) {
                assertThat(top.map(path -> path.getFileName().toString())).containsExactly("acme");
            }
            try (Stream<Path> tenant = Files.list(configuredSpaceRoot.resolve("acme"))) {
                assertThat(tenant.map(path -> path.getFileName().toString())).containsExactly("main");
            }
        } finally {
            if (server != null) {
                server.close();
            }
            System.clearProperty("JENESIS_STORE_ROOT");
            System.clearProperty("jenesis.repository.tenant");
            System.clearProperty("jenesis.repository.repository");
        }
    }

    @Test
    public void a_route_requires_tenant_repository_store_and_path() {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? routeStoreRoot.toString() : null);
        assertThatNullPointerException().isThrownBy(() -> new RepositoryRouting.Route(null, "repo", store, "/"));
        assertThatNullPointerException().isThrownBy(() -> new RepositoryRouting.Route("tenant", null, store, "/"));
        assertThatNullPointerException().isThrownBy(() -> new RepositoryRouting.Route("tenant", "repo", null, "/"));
        assertThatNullPointerException().isThrownBy(() -> new RepositoryRouting.Route("tenant", "repo", store, null));
        RepositoryRouting.Route route = new RepositoryRouting.Route("tenant", "repo", store, "/");
        assertThat(route.tenant()).isEqualTo("tenant");
        assertThat(route.repository()).isEqualTo("repo");
    }

    private static void roundTrip(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        byte[] body = {1, 2, 3, 4};
        HttpResponse<byte[]> put = client.send(
                HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(put.statusCode()).isEqualTo(201);
        HttpResponse<byte[]> get = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(body);
    }
}
