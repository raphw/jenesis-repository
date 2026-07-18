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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ServiceLoader resolution of the store backend: the bundled filesystem provider answers to its own name and reads its
 * root from the config lookup. No backend selected (null/blank) falls back to filesystem so a default deployment always
 * has a working store, but an <em>explicitly named</em> backend that no provider answers to fails loudly rather than
 * silently persisting against the local disk (a misconfigured {@code store=s3} must not boot against ephemeral storage).
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
    void no_backend_selected_falls_back_to_the_filesystem_provider() throws IOException {
        for (String unselected : new String[] {null, "", "  "}) {
            ArtifactStore store = ArtifactStoreProvider.resolve(
                    unselected, key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
            store.write("blobs/y", new ByteArrayInputStream("yo".getBytes(StandardCharsets.UTF_8)));
            assertThat(store.exists("blobs/y")).isTrue();
        }
    }

    @Test
    void an_explicitly_named_backend_with_no_provider_fails_loudly() {
        // A misconfigured or misspelled explicit selection must not silently serve and persist against the local
        // filesystem while the intended bucket 404s - it fails loudly, naming the backend it could not resolve.
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve(
                "does-not-exist", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }
}
