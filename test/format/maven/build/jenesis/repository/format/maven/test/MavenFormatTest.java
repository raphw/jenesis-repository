package build.jenesis.repository.format.maven.test;

import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven layout driven through {@link MavenFormat#handle}: a jar PUT is stored content-addressed and served back
 * (201/200), a missing artifact is a 404, an upload to a {@code maven-metadata.xml} path is dropped (the metadata is
 * derived), and a GET of that path generates the metadata on read from the published version folders.
 */
class MavenFormatTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final MavenFormat format = new MavenFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void name_and_handles_claim_only_the_maven_prefix() {
        assertThat(format.name()).isEqualTo("maven");
        assertThat(format.handles("/maven/org/example/lib/1.0/lib-1.0.jar")).isTrue();
        assertThat(format.handles("/raw/x")).isFalse();
    }

    @Test
    void a_jar_is_published_and_served_and_a_miss_is_404() throws IOException {
        byte[] jar = "plain jar bytes".getBytes(StandardCharsets.UTF_8);

        FakeExchange put = new FakeExchange("PUT", "/maven/org/example/lib/1.0/lib-1.0.jar", jar);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        FakeExchange get = new FakeExchange("GET", "/maven/org/example/lib/1.0/lib-1.0.jar");
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(jar);

        FakeExchange miss = new FakeExchange("GET", "/maven/org/example/lib/1.0/absent.jar");
        format.handle(miss, store);
        assertThat(miss.status()).isEqualTo(404);
    }

    @Test
    void describe_maps_a_maven_path_to_its_neutral_coordinate() {
        assertThat(format.describe("/maven/org/example/lib/1.0/lib-1.0.jar")).hasValueSatisfying(descriptor -> {
            assertThat(descriptor.ecosystem()).isEqualTo("maven");
            assertThat(descriptor.coordinate()).isEqualTo("org.example:lib");
            assertThat(descriptor.version()).isEqualTo("1.0");
            assertThat(descriptor.prerelease()).isFalse();
            assertThat(descriptor.path()).isEqualTo("/maven/org/example/lib/1.0/lib-1.0.jar");
        });

        assertThat(format.describe("/maven/org/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.prerelease())
                        .as("a SNAPSHOT is a prerelease").isTrue());

        assertThat(format.describe("/maven/org/example/lib/maven-metadata.xml"))
                .as("generated metadata carries no coordinate to describe").isEmpty();
    }

    @Test
    void paths_returns_the_maven_directory_a_version_occupies() {
        assertThat(format.paths("org.example:lib", "1.0")).containsExactly("/maven/org/example/lib/1.0");
    }

    @Test
    void a_metadata_upload_is_dropped_and_generated_on_read() throws IOException {
        FakeExchange jar = new FakeExchange(
                "PUT", "/maven/org/example/lib/1.0/lib-1.0.jar", "jar".getBytes(StandardCharsets.UTF_8));
        format.handle(jar, store);

        FakeExchange metaPut = new FakeExchange(
                "PUT", "/maven/org/example/lib/maven-metadata.xml", "<metadata/>".getBytes(StandardCharsets.UTF_8));
        format.handle(metaPut, store);
        assertThat(metaPut.status()).isEqualTo(201);
        assertThat(publication.located("/maven/org/example/lib/maven-metadata.xml"))
                .as("a metadata upload is derived, not stored").isEmpty();

        FakeExchange metaGet = new FakeExchange("GET", "/maven/org/example/lib/maven-metadata.xml");
        format.handle(metaGet, store);
        assertThat(metaGet.status()).isEqualTo(200);
        assertThat(metaGet.responseText())
                .contains("<artifactId>lib</artifactId>")
                .contains("<version>1.0</version>");
    }
}
