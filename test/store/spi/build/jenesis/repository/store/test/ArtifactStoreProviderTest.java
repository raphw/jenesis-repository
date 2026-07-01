package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ServiceLoader resolution of the store backend: the bundled filesystem provider answers to its own name and reads its
 * root from the config lookup, and an unknown backend name falls back to it rather than failing, so a default
 * deployment always has a working store.
 */
class ArtifactStoreProviderTest {

    @TempDir
    Path root;

    @Test
    void the_filesystem_backend_resolves_by_name_and_reads_its_root_from_config() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        store.write("blobs/x", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.exists("blobs/x")).isTrue();
    }

    @Test
    void an_unknown_backend_falls_back_to_the_filesystem_provider() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "does-not-exist", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        store.write("blobs/y", new ByteArrayInputStream("yo".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.exists("blobs/y")).isTrue();
    }
}
