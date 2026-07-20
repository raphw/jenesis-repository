package build.jenesis.repository.test;

import build.jenesis.RepositoryItem;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.module.JenesisModuleRepository;
import build.jenesis.repository.server.RepositoryApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real {@link RepositoryApplication} on an ephemeral port over a temporary filesystem store, publishes
 * artifacts over HTTP (the deploy side), then resolves them through the genuine Jenesis build-side clients -
 * {@link JenesisModuleRepository} for the module layout and {@link MavenDefaultRepository} for the Maven layout,
 * the same code a build's dependency resolution runs. So the one required cross-publish is proven against the
 * assembled server, off a single content-addressed blob: a Maven library carrying an {@code Automatic-Module-Name}
 * is resolvable by module name (and via the latest mirror). A module published to the module layout stays there and
 * is not mirrored to the Maven layout.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryDualLayoutE2ETest {

    @TempDir
    private static Path store;

    @TempDir
    private static Path mavenLocal;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;
    private final Executor executor = Runnable::run;

    @BeforeAll
    public void boot() {
        System.setProperty("jenesis.repository.insecure", "true");
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port() + "/repository/";
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("jenesis.repository.insecure");
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void a_maven_library_with_a_module_name_is_consumable_from_the_module_layout() throws IOException {
        byte[] jar = automaticModuleJar("test.widget");
        publish("maven/org/example/widget/1.0/widget-1.0.jar", jar);

        assertThat(maven().fetch(executor, "org.example", "widget", "1.0", "jar", null, null))
                .hasValueSatisfying(item -> assertThat(bytes(item)).isEqualTo(jar));

        assertThat(module().fetch(executor, "test.widget/1.0"))
                .hasValueSatisfying(item -> assertThat(bytes(item)).isEqualTo(jar));
        assertThat(module().fetch(executor, "test.widget"))
                .hasValueSatisfying(item -> assertThat(bytes(item)).isEqualTo(jar));
    }

    @Test
    public void a_module_published_to_the_module_layout_is_served_there_and_not_mirrored_to_maven() throws IOException {
        byte[] jar = automaticModuleJar("test.gadget");
        publish("module/test.gadget/2.0/test.gadget.jar", jar);

        assertThat(module().fetch(executor, "test.gadget/2.0"))
                .hasValueSatisfying(item -> assertThat(bytes(item)).isEqualTo(jar));

        assertThat(maven().fetch(executor, "test", "gadget", "2.0", "jar", null, null)).isEmpty();
    }

    @Test
    public void an_unpublished_coordinate_is_absent_in_both_layouts() throws IOException {
        assertThat(module().fetch(executor, "test.absent/9.9")).isEmpty();
        assertThat(maven().fetch(executor, "test", "absent", "9.9", "jar", null, null)).isEmpty();
    }

    private JenesisModuleRepository module() {
        return new JenesisModuleRepository(URI.create(base + "module/"));
    }

    private MavenDefaultRepository maven() {
        return new MavenDefaultRepository(URI.create(base + "maven/"), mavenLocal, Collections.emptyMap(), ignored -> {
        });
    }

    private void publish(String path, byte[] body) throws IOException {
        HttpResponse<Void> response;
        try {
            response = client.send(
                    HttpRequest.newBuilder(URI.create(base + path))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private static byte[] bytes(RepositoryItem item) {
        try (InputStream in = item.toInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] automaticModuleJar(String moduleName) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry(moduleName.replace('.', '/') + "/Marker.class"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
