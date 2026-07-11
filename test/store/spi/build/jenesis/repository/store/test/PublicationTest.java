package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void a_link_that_loses_a_compare_and_set_retries_and_lands() throws IOException {
        String hash = publication.storeBlob(bytes("contended"));
        ConflictingStore contended = new ConflictingStore(store, 1);

        new Publication(contended).link("/raw/contended", hash);

        assertThat(contended.conflicts).as("the first compare-and-set was made to conflict").isZero();
        assertThat(publication.blob("/raw/contended"))
                .as("the losing write re-read the token and retried rather than silently dropping").contains(hash);
    }

    @Test
    void a_link_that_cannot_land_surfaces_rather_than_silently_dropping() throws IOException {
        String hash = publication.storeBlob(bytes("unlandable"));
        Publication contended = new Publication(new ConflictingStore(store, Integer.MAX_VALUE));

        assertThatThrownBy(() -> contended.link("/raw/unlandable", hash))
                .as("persistent conflicts are an error the caller hears about, never a lost publish")
                .isInstanceOf(IOException.class);
        assertThat(publication.blob("/raw/unlandable")).isEmpty();
    }

    /** A store whose next {@code n} versioned writes report a benign compare-and-set conflict, then behave. */
    private static final class ConflictingStore implements ArtifactStore {

        private final ArtifactStore delegate;
        int conflicts;

        private ConflictingStore(ArtifactStore delegate, int conflicts) {
            this.delegate = delegate;
            this.conflicts = conflicts;
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (conflicts > 0) {
                conflicts--;
                return false;
            }
            return delegate.writeVersioned(key, content, expected);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }
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
