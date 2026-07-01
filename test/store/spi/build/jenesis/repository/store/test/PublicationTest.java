package build.jenesis.repository.store.test;

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
 * The content-addressed publication model over a real filesystem store on a {@code @TempDir}: a blob is stored once by
 * its SHA-256 and any number of request paths point at it, so a republish is a pointer update, an unpublish drops only
 * the pointer, and a path whose blob is gone resolves to nothing rather than a dangling key.
 */
class PublicationTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_stored_blob_is_located_through_the_pointer_it_is_linked_under() throws IOException {
        String hash = publication.storeBlob(bytes("payload"));
        assertThat(store.exists("blobs/" + hash)).isTrue();
        assertThat(publication.located("/raw/a/b")).as("an unlinked path resolves to nothing").isEmpty();

        publication.link("/raw/a/b", hash);
        assertThat(publication.blob("/raw/a/b")).contains(hash);
        assertThat(publication.located("/raw/a/b")).contains("blobs/" + hash);
    }

    @Test
    void identical_content_dedupes_to_one_blob_shared_by_several_paths() throws IOException {
        String first = publication.storeBlob(bytes("same"));
        String second = publication.storeBlob(bytes("same"));
        assertThat(second).as("identical content addresses to one blob").isEqualTo(first);

        publication.link("/module/x/1/x.jar", first);
        publication.link("/module/x/x.jar", first);
        assertThat(publication.located("/module/x/1/x.jar")).contains("blobs/" + first);
        assertThat(publication.located("/module/x/x.jar")).contains("blobs/" + first);
    }

    @Test
    void a_republish_is_a_pointer_update() throws IOException {
        String one = publication.storeBlob(bytes("one"));
        String two = publication.storeBlob(bytes("two"));
        publication.link("/raw/p", one);
        assertThat(publication.blob("/raw/p")).contains(one);

        publication.link("/raw/p", two);
        assertThat(publication.blob("/raw/p")).as("the pointer now names the new blob").contains(two);
        assertThat(publication.located("/raw/p")).contains("blobs/" + two);
    }

    @Test
    void unpublish_removes_the_pointer_and_located_reflects_a_missing_blob() throws IOException {
        String hash = publication.storeBlob(bytes("gone"));
        publication.link("/raw/q", hash);

        publication.unpublish("/raw/q");
        assertThat(publication.blob("/raw/q")).isEmpty();
        assertThat(publication.located("/raw/q")).isEmpty();

        publication.link("/raw/r", hash);
        store.delete("blobs/" + hash);
        assertThat(publication.blob("/raw/r")).as("the pointer still exists").contains(hash);
        assertThat(publication.located("/raw/r")).as("but the blob it referenced is gone").isEmpty();
    }
}
