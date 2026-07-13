package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.store.ReadOnlyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read-only decorator refuses every write ({@code write}, the content-addressed {@code writeBlob}, {@code delete}
 * and the compare-and-set {@code writeVersioned}) with {@link ReadOnlyException} and stores no bytes, while every read
 * passes straight through to the delegate; a scoped view stays read-only too, so a per-tenant / per-repository write is
 * refused just like a root one.
 */
class ReadOnlyArtifactStoreTest {

    @TempDir
    Path root;

    private ArtifactStore delegate() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(int length) {
        return new ByteArrayInputStream(new byte[length]);
    }

    @Test
    void every_write_is_refused_and_stores_nothing() throws IOException {
        ReadOnlyArtifactStore store = new ReadOnlyArtifactStore(delegate());
        assertThatThrownBy(() -> store.write("blobs/aaa", bytes(10))).isInstanceOf(ReadOnlyException.class);
        assertThatThrownBy(() -> store.writeBlob(bytes(10))).isInstanceOf(ReadOnlyException.class);
        assertThatThrownBy(() -> store.delete("blobs/aaa")).isInstanceOf(ReadOnlyException.class);
        assertThatThrownBy(() -> store.writeVersioned("meta", new byte[] {1}, null))
                .isInstanceOf(ReadOnlyException.class);
        assertThat(store.exists("blobs/aaa")).as("a refused write leaves no bytes").isFalse();
    }

    @Test
    void reads_pass_through_to_the_delegate() throws IOException {
        ArtifactStore raw = delegate();
        raw.write("blobs/aaa", bytes(300));
        raw.writeVersioned("meta", new byte[] {1, 2, 3}, null);
        ReadOnlyArtifactStore store = new ReadOnlyArtifactStore(raw);

        assertThat(store.exists("blobs/aaa")).isTrue();
        assertThat(store.size("blobs/aaa")).isEqualTo(300);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("blobs/aaa", out);
        assertThat(out.size()).isEqualTo(300);
        try (var in = store.open("blobs/aaa")) {
            assertThat(in.readAllBytes()).hasSize(300);
        }
        assertThat(store.list("blobs")).contains("aaa");
        assertThat(store.readVersioned("meta")).isPresent();
    }

    @Test
    void a_scoped_view_stays_read_only() {
        ReadOnlyArtifactStore store = new ReadOnlyArtifactStore(delegate());
        ArtifactStore scoped = store.scope("acme").scope("releases");
        assertThat(scoped).isInstanceOf(ReadOnlyArtifactStore.class);
        assertThatThrownBy(() -> scoped.write("blobs/bbb", bytes(10))).isInstanceOf(ReadOnlyException.class);
    }
}
