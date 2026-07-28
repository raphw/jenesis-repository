package build.jenesis.repository.format.jenesis.test;

import build.jenesis.repository.format.jenesis.JenesisFormat;
import build.jenesis.repository.format.jenesis.ModuleViewPublisher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jenesis module layout driven through {@link JenesisFormat#handle}: it claims the {@code /module/} and
 * {@code /artifact/} prefixes, a PUT stores the blob content-addressed and links the path (201), a GET serves it back
 * byte for byte (200), and a GET of an unpublished path is a miss (404).
 */
class JenesisFormatTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final JenesisFormat format = new JenesisFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void name_and_handles_claim_the_module_and_artifact_prefixes() {
        assertThat(format.name()).isEqualTo("jenesis");
        assertThat(format.handles("/module/com.acme/1.0/com.acme.jar")).isTrue();
        assertThat(format.handles("/artifact/anything")).isTrue();
        assertThat(format.handles("/maven/org/example")).isFalse();
    }

    @Test
    void a_module_is_stored_and_served_and_a_miss_is_404() throws IOException {
        byte[] body = "modular jar bytes".getBytes(StandardCharsets.UTF_8);

        FakeExchange put = new FakeExchange("PUT", "/module/com.acme/1.0/com.acme.jar", body);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        FakeExchange get = new FakeExchange("GET", "/module/com.acme/1.0/com.acme.jar");
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(body);

        FakeExchange miss = new FakeExchange("GET", "/module/com.acme/9.9/com.acme.jar");
        format.handle(miss, store);
        assertThat(miss.status()).isEqualTo(404);
    }

    @Test
    void a_head_is_answered_from_the_stored_size_without_streaming_the_blob() throws IOException {
        byte[] body = "modular jar bytes".getBytes(StandardCharsets.UTF_8);
        format.handle(new FakeExchange("PUT", "/module/com.acme/1.0/com.acme.jar", body), store);

        FakeExchange head = new FakeExchange("HEAD", "/module/com.acme/1.0/com.acme.jar");
        format.handle(head, store);
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.responseBytes()).as("a HEAD answers from metadata, never streaming the blob body").isEmpty();
        assertThat(head.responseHeader("Content-Length"))
                .as("Content-Length is the stored blob size").isEqualTo(Long.toString(body.length));

        FakeExchange miss = new FakeExchange("HEAD", "/module/com.acme/9.9/com.acme.jar");
        format.handle(miss, store);
        assertThat(miss.status()).isEqualTo(404);
    }

    @Test
    void the_artifact_layout_round_trips_the_same_way() throws IOException {
        byte[] body = {4, 5, 6, 7};

        FakeExchange put = new FakeExchange("PUT", "/artifact/com.acme/1.0/notes.txt", body);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        FakeExchange get = new FakeExchange("GET", "/artifact/com.acme/1.0/notes.txt");
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(body);
    }

    @Test
    void ecosystem_is_the_jenesis_osv_name() {
        assertThat(format.ecosystem()).isEqualTo("Jenesis");
    }

    @Test
    void describe_maps_a_module_path_to_its_neutral_coordinate() {
        assertThat(format.describe("/module/com.acme/1.0/com.acme.jar")).hasValueSatisfying(descriptor -> {
            assertThat(descriptor.ecosystem()).isEqualTo("Jenesis");
            assertThat(descriptor.coordinate()).isEqualTo("com.acme");
            assertThat(descriptor.version()).isEqualTo("1.0");
            assertThat(descriptor.prerelease()).isFalse();
            assertThat(descriptor.path()).isEqualTo("/module/com.acme/1.0/com.acme.jar");
        });

        assertThat(format.describe("/module/com.acme/com.acme.jar"))
                .as("the version-less latest pointer is described version-less")
                .hasValueSatisfying(descriptor -> {
                    assertThat(descriptor.ecosystem()).isEqualTo("Jenesis");
                    assertThat(descriptor.coordinate()).isEqualTo("com.acme");
                    assertThat(descriptor.version()).as("the latest pointer carries no version").isNull();
                    assertThat(descriptor.path()).isEqualTo("/module/com.acme/com.acme.jar");
                });
    }

    @Test
    void describe_does_not_claim_a_non_jenesis_or_directory_path() {
        assertThat(format.describe("/maven/org/example/lib/1.0/lib-1.0.jar"))
                .as("a non-jenesis path is not claimed").isEmpty();
        assertThat(format.describe("/module/com.acme/1.0"))
                .as("a version directory carries no coordinate to describe").isEmpty();
        assertThat(format.describe("/artifact/com.acme/1.0/notes.txt"))
                .as("the /artifact/ layout carries no module coordinate").isEmpty();
    }

    @Test
    void paths_round_trip_the_two_module_view_link_shapes() {
        // The version directory holding the versioned jar and the version-less latest pointer file - exactly the two
        // links ModuleViewPublisher publishes for a module version, recovered from the coordinate alone.
        assertThat(format.paths("com.acme", "1.0"))
                .containsExactly("/module/com.acme/1.0", "/module/com.acme/com.acme.jar");
        assertThat(format.paths("com.acme", "1.0", store))
                .as("the store overload adds no cross-published mirror for jenesis")
                .containsExactly("/module/com.acme/1.0", "/module/com.acme/com.acme.jar");
        assertThat(format.paths("", "1.0")).as("an empty coordinate maps nowhere").isEmpty();
    }

    @Test
    void paths_prefixes_cover_exactly_the_pointers_a_module_view_publish_links() throws IOException {
        // Publish the two links the way the Maven cross-publish does, then prove paths()'s prefixes cover exactly them:
        // link 1 (the versioned jar) lives under the version-directory prefix, link 2 is the latest-pointer file itself.
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream("modular jar".getBytes(StandardCharsets.UTF_8)));
        new ModuleViewPublisher().publish("com.acme", "1.0", hash, store);
        assertThat(publication.located("/module/com.acme/1.0/com.acme.jar")).contains("blobs/" + hash);
        assertThat(publication.located("/module/com.acme/com.acme.jar")).contains("blobs/" + hash);

        List<String> prefixes = format.paths("com.acme", "1.0", store);
        assertThat("/module/com.acme/1.0/com.acme.jar".startsWith(prefixes.getFirst() + "/"))
                .as("the versioned jar lives under the version-directory prefix").isTrue();
        assertThat(prefixes).as("the latest pointer file is a prefix of itself")
                .contains("/module/com.acme/com.acme.jar");
    }
}
