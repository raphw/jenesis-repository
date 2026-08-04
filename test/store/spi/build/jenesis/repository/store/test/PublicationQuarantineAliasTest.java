package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Publication#quarantineAliasExists} (Audit-26 F3): the free-store cross-alias scan an automated content-addressed
 * marker clear runs before lifting the marker. It walks the {@code publish/quarantine} pointer subtree for any live
 * review pointer OUTSIDE the caller's own served paths whose body is the hash, short-circuiting on the first, skipping a
 * garbled pointer key defensively, and propagating a genuine store {@link IOException} so the caller fails closed (does
 * not clear).
 */
class PublicationQuarantineAliasTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;

    private static final String HASH = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    private void quarantine(String servedPath, String hash) throws IOException {
        store.write("publish/quarantine" + servedPath, new ByteArrayInputStream(hash.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_live_sibling_pointer_holding_the_hash_is_found() throws IOException {
        quarantine("/v2/sibling/app/manifests/1.0", HASH);
        assertThat(publication.quarantineAliasExists(HASH, Set.of("/v2/self/app/manifests/1.0")))
                .as("a sibling review pointer whose body is the hash keeps the marker").isTrue();
    }

    @Test
    void an_empty_quarantine_subtree_holds_nothing() throws IOException {
        assertThat(publication.quarantineAliasExists(HASH, Set.of())).isFalse();
    }

    @Test
    void the_callers_own_excluded_path_does_not_count_as_an_alias() throws IOException {
        quarantine("/v2/self/app/manifests/1.0", HASH);
        assertThat(publication.quarantineAliasExists(HASH, Set.of("/v2/self/app/manifests/1.0")))
                .as("the caller's own pointer is excluded, so it does not hold the marker on its own account").isFalse();
    }

    @Test
    void a_pointer_holding_a_different_hash_is_not_an_alias() throws IOException {
        quarantine("/v2/sibling/app/manifests/1.0", OTHER);
        assertThat(publication.quarantineAliasExists(HASH, Set.of())).isFalse();
    }

    @Test
    void a_garbled_pointer_key_is_skipped_rather_than_thrown_out_of_the_guard() throws IOException {
        // A pointer whose read throws a RuntimeException (an InvalidPathException out of a hostile key) is contained: the
        // scan skips that one entry and reports no alias, never propagating the RuntimeException out of the guard.
        assertThat(new Publication(new WalkStore(new InvalidPathException("x", "hostile"), null))
                .quarantineAliasExists(HASH, Set.of())).isFalse();
    }

    @Test
    void a_genuine_store_io_exception_propagates_so_the_caller_fails_closed() {
        IOException failure = new IOException("store down");
        assertThatThrownBy(() -> new Publication(new WalkStore(null, failure))
                .quarantineAliasExists(HASH, Set.of()))
                .as("an IOException propagates: the caller does NOT clear (fail-closed)").isSameAs(failure);
    }

    /** A store presenting one {@code publish/quarantine/v2/app/manifests/1.0} leaf whose {@code readVersioned} throws the
     *  configured RuntimeException or IOException, exercising the scan's contain-vs-propagate split; every other method
     *  is inert. */
    private static final class WalkStore implements ArtifactStore {

        private final RuntimeException runtimeOnRead;
        private final IOException ioOnRead;

        WalkStore(RuntimeException runtimeOnRead, IOException ioOnRead) {
            this.runtimeOnRead = runtimeOnRead;
            this.ioOnRead = ioOnRead;
        }

        @Override
        public List<String> list(String prefix) {
            return switch (prefix) {
                case "publish/quarantine" -> List.of("v2");
                case "publish/quarantine/v2" -> List.of("app");
                case "publish/quarantine/v2/app" -> List.of("manifests");
                case "publish/quarantine/v2/app/manifests" -> List.of("1.0");
                default -> List.of();
            };
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            if (key.equals("publish/quarantine/v2/app/manifests/1.0")) {
                if (ioOnRead != null) {
                    throw ioOnRead;
                }
                if (runtimeOnRead != null) {
                    throw runtimeOnRead;
                }
            }
            return Optional.empty();
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public void read(String key, OutputStream out) {
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void write(String key, InputStream in) {
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size(String key) {
            return -1L;
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            return false;
        }
    }
}
