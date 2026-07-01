package build.jenesis.repository.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.QuotaExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The quota decorator meters only content blobs against a byte ceiling, tracks usage through writes and deletes,
 * dedupes a re-written blob, refuses a new blob once the scope is at the limit while letting a write begun under it
 * complete, meters the content-addressed streaming write the same way, treats a non-positive limit as unlimited, and
 * reseeds from the live blobs with recompute.
 */
class QuotaArtifactStoreTest {

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
    void only_blob_bytes_count_and_usage_tracks_writes_and_deletes() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 1000);
        store.write("publish/maven/x", bytes(500));
        assertThat(store.used()).as("a pointer is not a blob").isZero();
        store.write("blobs/aaa", bytes(300));
        store.write("blobs/bbb", bytes(200));
        assertThat(store.used()).isEqualTo(500);
        store.delete("blobs/aaa");
        assertThat(store.used()).isEqualTo(200);
    }

    @Test
    void a_re_written_blob_does_not_double_count() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 1000);
        store.write("blobs/aaa", bytes(300));
        store.write("blobs/aaa", bytes(300));
        assertThat(store.used()).isEqualTo(300);
    }

    @Test
    void a_streamed_blob_is_metered_and_dedupes_like_a_keyed_write() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 1000);
        String first = store.writeBlob(bytes(300));
        String again = store.writeBlob(bytes(300));
        assertThat(again).as("identical content dedupes to one blob").isEqualTo(first);
        assertThat(store.exists("blobs/" + first)).isTrue();
        assertThat(store.used()).as("the streaming write counts once, like a keyed write").isEqualTo(300);
    }

    @Test
    void a_streamed_blob_is_refused_once_the_scope_is_at_the_limit() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 500);
        store.write("blobs/aaa", bytes(500));
        assertThatThrownBy(() -> store.writeBlob(bytes(1)))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(store.used()).isEqualTo(500);
    }

    @Test
    void a_new_blob_is_refused_once_the_scope_is_at_the_limit() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 500);
        store.write("blobs/aaa", bytes(500));
        assertThatThrownBy(() -> store.write("blobs/bbb", bytes(1)))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(store.exists("blobs/bbb")).as("no partial bytes stored").isFalse();
        assertThat(store.used()).isEqualTo(500);
    }

    @Test
    void a_write_begun_under_the_limit_completes_even_if_it_crosses() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 500);
        store.write("blobs/aaa", bytes(499));
        store.write("blobs/bbb", bytes(1000));
        assertThat(store.used()).isEqualTo(1499);
    }

    @Test
    void a_non_positive_limit_is_unlimited_and_unmetered() throws IOException {
        QuotaArtifactStore store = new QuotaArtifactStore(delegate(), 0);
        store.write("blobs/aaa", bytes(10_000));
        store.write("blobs/bbb", bytes(10_000));
        assertThat(store.exists("blobs/bbb")).isTrue();
        assertThat(store.used()).as("unlimited keeps no counter").isZero();
    }

    @Test
    void scopes_below_a_wrapped_root_share_one_counter_and_one_limit() throws IOException {
        QuotaArtifactStore tenant = new QuotaArtifactStore(delegate().scope("acme"), 1000);
        ArtifactStore releases = tenant.scope("releases");
        ArtifactStore snapshots = tenant.scope("snapshots");

        releases.write("blobs/aaa", bytes(400));
        snapshots.write("blobs/bbb", bytes(300));
        assertThat(tenant.used()).as("both repositories count against the tenant counter").isEqualTo(700);

        releases.write("blobs/ccc", bytes(300));
        assertThatThrownBy(() -> snapshots.write("blobs/ddd", bytes(1)))
                .as("the limit is shared across the tenant's repositories")
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void recompute_seeds_usage_from_the_live_blobs() throws IOException {
        ArtifactStore raw = delegate();
        raw.write("blobs/aaa", bytes(300));
        raw.write("blobs/bbb", bytes(200));
        QuotaArtifactStore store = new QuotaArtifactStore(raw, 1000);
        assertThat(store.used()).as("counter not yet seeded").isZero();
        assertThat(store.recompute()).isEqualTo(500);
        assertThat(store.used()).isEqualTo(500);
    }
}
