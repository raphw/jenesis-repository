package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code presign} SPI seam (EPIC 29 RD-1): the default answers empty so a backend that cannot mint a direct-fetch
 * URL - the filesystem store - streams as today, and every read-only decorator delegates it, signing the
 * <em>fully-qualified</em> object key so a tenant-scoped presign carries the scope prefix down to the signing backend.
 * Hermetic: the signing backend is a recording stub that captures the key it was asked to sign, so no live object store
 * (S3 presigner, Azurite) is needed to prove the scope prefix and the decorator delegation reach the leaf.
 */
class PresignDelegationTest {

    private static final Duration TTL = Duration.ofSeconds(120);

    @TempDir
    Path root;

    @Test
    void the_spi_default_is_empty_so_a_filesystem_backend_streams_as_today() {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        assertThat(filesystem.presign("blobs/x", TTL)).as("the filesystem backend cannot presign").isEmpty();
        assertThat(filesystem.scope("acme").presign("blobs/x", TTL)).as("a scoped view too").isEmpty();
    }

    @Test
    void the_read_only_decorator_delegates_presign_of_the_fully_qualified_scoped_key() {
        RecordingPresignStore leaf = new RecordingPresignStore();
        ArtifactStore store = new ReadOnlyArtifactStore(leaf);
        Optional<URI> url = store.scope("t").presign("blobs/x", TTL);
        assertThat(url).as("the read-only wrapper delegates presign rather than answering the default empty")
                .contains(URI.create("https://signed/t/blobs/x"));
        assertThat(leaf.signed).as("the tenant prefix reaches the signing backend").containsExactly("t/blobs/x");
    }

    @Test
    void the_quota_decorator_delegates_presign_of_the_fully_qualified_scoped_key() {
        RecordingPresignStore leaf = new RecordingPresignStore();
        ArtifactStore store = new QuotaArtifactStore(leaf, 1000);
        Optional<URI> url = store.scope("t").presign("blobs/x", TTL);
        assertThat(url).as("the quota wrapper delegates presign - a read-only capability it has no reason to block")
                .contains(URI.create("https://signed/t/blobs/x"));
        assertThat(leaf.signed).containsExactly("t/blobs/x");
    }

    @Test
    void stacked_read_only_over_quota_still_signs_the_scoped_key() {
        RecordingPresignStore leaf = new RecordingPresignStore();
        ArtifactStore store = new ReadOnlyArtifactStore(new QuotaArtifactStore(leaf, 1000));
        assertThat(store.scope("t").presign("blobs/x", TTL)).contains(URI.create("https://signed/t/blobs/x"));
        assertThat(leaf.signed).containsExactly("t/blobs/x");
    }

    /**
     * A minimal {@link ArtifactStore} that mints a deterministic {@code https://signed/<key>} URL for the
     * fully-qualified key and records it, standing in for an object-store backend's presigner. {@link #scope} applies
     * a key prefix exactly as the real backends do, sharing the recording list so a scoped view records against the
     * same instance; every other method is unused by these tests.
     */
    private static final class RecordingPresignStore implements ArtifactStore {

        private final List<String> signed;
        private final String keyPrefix;

        private RecordingPresignStore() {
            this(new ArrayList<>(), "");
        }

        private RecordingPresignStore(List<String> signed, String keyPrefix) {
            this.signed = signed;
            this.keyPrefix = keyPrefix;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new RecordingPresignStore(signed, keyPrefix + ArtifactStore.segment(tenant) + "/");
        }

        @Override
        public Optional<URI> presign(String key, Duration ttl) {
            String qualified = keyPrefix + key;
            signed.add(qualified);
            return Optional.of(URI.create("https://signed/" + qualified));
        }

        @Override
        public boolean exists(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void read(String key, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream open(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String key, InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> list(String prefix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            throw new UnsupportedOperationException();
        }
    }
}
