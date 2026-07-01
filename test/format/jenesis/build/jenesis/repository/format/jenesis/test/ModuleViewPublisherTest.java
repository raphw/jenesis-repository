package build.jenesis.repository.format.jenesis.test;

import build.jenesis.repository.format.jenesis.ModuleViewPublisher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jenesis format's cross-publish contribution: given an already-stored blob, {@code publish} links a modular jar
 * into the module layout both by module name and version and by module name alone (the latest), so a client resolving
 * either way reaches the same content-addressed blob.
 */
class ModuleViewPublisherTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final ModuleViewPublisher publisher = new ModuleViewPublisher();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void publish_links_the_versioned_and_the_latest_module_view_to_one_blob() throws IOException {
        String hash = publication.storeBlob(
                new ByteArrayInputStream("modular jar".getBytes(StandardCharsets.UTF_8)));

        publisher.publish("com.acme.lib", "1.0", hash, store);

        assertThat(publication.located("/module/com.acme.lib/1.0/com.acme.lib.jar")).contains("blobs/" + hash);
        assertThat(publication.located("/module/com.acme.lib/com.acme.lib.jar")).contains("blobs/" + hash);
    }
}
