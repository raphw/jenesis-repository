package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hardening guarantees for the OCI format (W7.0 group 2): a blob/manifest reference that is not a real sha256 digest is
 * refused before it becomes a {@code blobs/<hex>} key (so a {@code ..}-laced digest cannot aim the read at a
 * neighbouring key space), and a tag pointer survives a lost compare-and-set by retrying (the {@code Publication.link}
 * idiom) rather than a concurrent push silently dropping the re-tag while still answering 201.
 */
class OciHardeningTest {

    @TempDir
    Path root;

    private final OciFormat format = new OciFormat();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void a_traversal_digest_cannot_read_a_neighbouring_key() throws IOException {
        ArtifactStore store = store();
        // Push a manifest under tag `latest`, so the tag pointer object oci/app/tags/latest exists.
        byte[] manifest = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);
        FakeExchange push = new FakeExchange("PUT", "/v2/app/manifests/latest", manifest,
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(push, store);
        assertThat(push.status()).isEqualTo(201);

        // A digest of `sha256:../oci/app/tags/latest` would, unguarded, resolve blobs/../oci/app/tags/latest to the
        // tag pointer and serve it as a blob. The hex guard refuses it as not-a-digest.
        FakeExchange leak = new FakeExchange("GET", "/v2/app/blobs/sha256:../oci/app/tags/latest");
        format.handle(leak, store);
        assertThat(leak.status()).as("a non-hex/traversal digest is refused, not resolved").isEqualTo(404);

        // A too-short and a non-hex-character digest are likewise refused.
        FakeExchange shortDigest = new FakeExchange("GET", "/v2/app/blobs/sha256:abcd");
        format.handle(shortDigest, store);
        assertThat(shortDigest.status()).isEqualTo(404);

        FakeExchange nonHex = new FakeExchange("GET", "/v2/app/manifests/sha256:"
                + "zz".repeat(32));
        format.handle(nonHex, store);
        assertThat(nonHex.status()).isEqualTo(404);
    }

    @Test
    void a_traversal_image_name_is_refused() throws IOException {
        ArtifactStore store = store();
        // The image name is spliced into oci/<name>/tags/<ref> and oci/uploads keys. Unlike the tag and digest it may
        // legitimately contain '/', so a '..' segment inside it is not caught by rejecting '/'; the per-segment name
        // guard refuses it before it builds a key that, on a path-normalising backend, would traverse out of oci/.
        byte[] manifest = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);
        FakeExchange push = new FakeExchange("PUT", "/v2/../../publish/raw/evil/manifests/latest", manifest,
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(push, store);
        assertThat(push.status()).as("a '..'-laced image name is refused").isEqualTo(400);
        assertThat(store.list("publish")).as("nothing is planted in the publish namespace").isEmpty();

        // A manifest read and a tags listing under a traversal name are refused too.
        FakeExchange get = new FakeExchange("GET", "/v2/a/../../evil/manifests/latest");
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(400);

        // A legitimate multi-segment name is still accepted.
        FakeExchange ok = new FakeExchange("PUT", "/v2/library/ubuntu/manifests/latest", manifest,
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(ok, store);
        assertThat(ok.status()).as("a normal library/ubuntu name is accepted").isEqualTo(201);
    }

    @Test
    void a_traversal_upload_session_is_refused() throws IOException {
        ArtifactStore store = store();
        // The upload session id builds the oci/uploads/<id>/... chunk keys whose bytes the client controls. A client
        // that invents a '..'-laced id the server never issued is refused before any chunk write.
        FakeExchange patch = new FakeExchange("PATCH", "/v2/app/blobs/uploads/../../publish/raw/evil",
                new byte[]{1, 2, 3}, Map.of(), Map.of());
        format.handle(patch, store);
        assertThat(patch.status()).as("a '..'-laced upload session is refused").isEqualTo(400);
        assertThat(store.list("publish")).as("no chunk is planted in the publish namespace").isEmpty();
    }

    @Test
    void a_tag_pointer_survives_a_lost_first_compare_and_set() throws IOException {
        ConflictOnceStore store = new ConflictOnceStore(store());
        byte[] manifest = "{\"schemaVersion\":2,\"mediaType\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        FakeExchange push = new FakeExchange("PUT", "/v2/app/manifests/1.0", manifest,
                Map.of(), Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
        format.handle(push, store);
        assertThat(push.status()).isEqualTo(201);
        assertThat(store.conflictsInjected).as("the first tag CAS was forced to lose").isEqualTo(1);

        // The old code dropped the tag pointer on that lost CAS and a pull-by-tag 404ed; the retry lands it.
        FakeExchange byTag = new FakeExchange("GET", "/v2/app/manifests/1.0");
        format.handle(byTag, store);
        assertThat(byTag.status()).as("the tag resolves - the pointer landed despite the lost CAS").isEqualTo(200);
        assertThat(byTag.responseBytes()).isEqualTo(manifest);
    }

    /** A store decorator that fails the very first {@code writeVersioned} once - the concurrent conflict the tag-link
     *  retry absorbs - then delegates untouched. */
    private static final class ConflictOnceStore implements ArtifactStore {

        private final ArtifactStore delegate;
        private boolean armed = true;
        private int conflictsInjected;

        private ConflictOnceStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (armed) {
                armed = false;
                conflictsInjected++;
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
}
