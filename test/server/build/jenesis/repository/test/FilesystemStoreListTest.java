package build.jenesis.repository.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filesystem store's list must not leak the temporary file an atomic write leaves in a directory until it
 * renames it into place. A status poll that lists a directory while a write is in flight there would otherwise see
 * a half-written {@code .upload*.tmp} as if it were a stored entry and read its partial bytes - the cause of an
 * intermittent parse failure when listing import jobs. This plants the temp file by hand and asserts list ignores
 * it, the directory entry an atomic write transiently produces.
 */
class FilesystemStoreListTest {

    @TempDir
    Path root;

    @Test
    void list_ignores_an_atomic_write_temp_file() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        store.write("imports/job-1", new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        Files.createFile(root.resolve("imports").resolve(".upload12345.tmp"));

        assertThat(store.list("imports")).containsExactly("job-1");
    }
}
