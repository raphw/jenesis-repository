package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.ServableNames.Policy;
import build.jenesis.repository.store.ServableNames.State;
import build.jenesis.repository.store.Withheld;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The servable-name enumeration seam: {@link ServableNames} must discriminate the tri-state
 * (servable / withheld / blob-gone / unpublished) both {@code Publication.located} conflates, across both the
 * {@code publish/}-namespace face ({@link ServableNames#state}) and the {@code blobs/}-namespace marker face
 * ({@link ServableNames#keyState}); it must answer the {@link Policy#HIDE_WITHHELD} membership question with
 * <em>zero</em> blob-stat I/O (so a fake-hash member keeps listing and a hot listing pays no download-shaped cost);
 * it must contain a hostile / unresolvable name (fail-closed skip, never a 500); its version-folder face must hide a
 * held version through the {@code /quarantine} review-pointer convention while keeping a fake-hash/no-blob or empty
 * folder; and its blobs-namespace face must agree bit-for-bit with the hand-derived {@code Blobs.read} discrimination.
 * The seam is what serve and enumeration now share, so {@code located} empty must be exactly {@code state != SERVABLE}.
 */
class ServableNamesTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_GONE = "c".repeat(64);

    // ---- publish/-namespace tri-state (state) ------------------------------------------------------------------

    @Test
    void state_discriminates_the_publish_namespace_tri_state_the_serve_path_conflates() throws IOException {
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/1/served.jar", HASH_A);
        store.blob(HASH_A);
        store.pointer("publish/maven/g/a/1/gone.jar", HASH_GONE);          // pointer, no blob
        store.pointer("publish/maven/g/a/1/held.jar", HASH_B);
        store.blob(HASH_B);

        Withholding chain = new Withholding("/maven/g/a/1/held.jar");
        ServableNames names = new ServableNames(store, new Publication(store, List.of(chain)));

        assertThat(names.state("/maven/g/a/1/served.jar")).isEqualTo(State.SERVABLE);
        assertThat(names.state("/maven/g/a/1/held.jar")).isEqualTo(State.WITHHELD);
        assertThat(names.state("/maven/g/a/1/gone.jar")).isEqualTo(State.BLOB_GONE);
        assertThat(names.state("/maven/g/a/1/never.jar")).isEqualTo(State.UNPUBLISHED);
    }

    @Test
    void located_is_empty_exactly_when_state_is_not_servable_so_serve_and_enumeration_share_one_truth()
            throws IOException {
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/1/served.jar", HASH_A);
        store.blob(HASH_A);
        store.pointer("publish/maven/g/a/1/gone.jar", HASH_GONE);
        store.pointer("publish/maven/g/a/1/held.jar", HASH_B);
        store.blob(HASH_B);

        Withholding chain = new Withholding("/maven/g/a/1/held.jar");
        Publication publication = new Publication(store, List.of(chain));
        ServableNames names = new ServableNames(store, publication);

        for (String path : List.of("/maven/g/a/1/served.jar", "/maven/g/a/1/gone.jar",
                "/maven/g/a/1/held.jar", "/maven/g/a/1/never.jar")) {
            boolean servable = names.state(path) == State.SERVABLE;
            assertThat(publication.located(path).isPresent())
                    .as("located(%s) present must equal state==SERVABLE", path)
                    .isEqualTo(servable);
        }
        assertThat(publication.located("/maven/g/a/1/served.jar")).contains("blobs/" + HASH_A);
    }

    // ---- blobs/-namespace tri-state (keyState) and Blobs.read parity -------------------------------------------

    @Test
    void keyState_matches_the_hand_written_blobs_read_shaped_discrimination() throws IOException {
        MapStore store = new MapStore();
        store.pointer("npm/pkg/tarballs/served.tgz", HASH_A);
        store.blob(HASH_A);
        store.pointer("npm/pkg/tarballs/held.tgz", HASH_B);
        store.blob(HASH_B);
        Withheld.mark(store, HASH_B);                                       // the withheld/<hash> marker
        store.pointer("npm/pkg/tarballs/gone.tgz", HASH_GONE);             // pointer, no blob, no marker

        ServableNames names = new ServableNames(store);

        // A hand-written Blobs.read-shaped expectation: pointer absent -> UNPUBLISHED; marker -> WITHHELD;
        // blob missing -> BLOB_GONE; else SERVABLE. keyState must agree with it, key for key.
        assertThat(names.keyState("npm/pkg/tarballs/served.tgz")).isEqualTo(expected(store, "npm/pkg/tarballs/served.tgz"));
        assertThat(names.keyState("npm/pkg/tarballs/held.tgz")).isEqualTo(expected(store, "npm/pkg/tarballs/held.tgz"));
        assertThat(names.keyState("npm/pkg/tarballs/gone.tgz")).isEqualTo(expected(store, "npm/pkg/tarballs/gone.tgz"));
        assertThat(names.keyState("npm/pkg/tarballs/absent.tgz")).isEqualTo(expected(store, "npm/pkg/tarballs/absent.tgz"));

        // And the concrete states, so the parity function above is not the only witness.
        assertThat(names.keyState("npm/pkg/tarballs/served.tgz")).isEqualTo(State.SERVABLE);
        assertThat(names.keyState("npm/pkg/tarballs/held.tgz")).isEqualTo(State.WITHHELD);
        assertThat(names.keyState("npm/pkg/tarballs/gone.tgz")).isEqualTo(State.BLOB_GONE);
        assertThat(names.keyState("npm/pkg/tarballs/absent.tgz")).isEqualTo(State.UNPUBLISHED);
    }

    /** The {@code Blobs.read}/{@code Blobs.size} discrimination, hand-rolled independently of the seam, so
     *  {@link ServableNames#keyState} is checked against a second implementation rather than against itself. */
    private static State expected(ArtifactStore store, String pointerKey) throws IOException {
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey);
        if (pointer.isEmpty()) {
            return State.UNPUBLISHED;
        }
        String hash = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
        if (store.readVersioned("withheld/" + hash).isPresent()) {
            return State.WITHHELD;
        }
        return store.exists("blobs/" + hash) ? State.SERVABLE : State.BLOB_GONE;
    }

    @Test
    void withheldHash_reads_the_marker_convention() throws IOException {
        MapStore store = new MapStore();
        store.blob(HASH_A);
        store.blob(HASH_B);
        Withheld.mark(store, HASH_B);
        ServableNames names = new ServableNames(store);

        assertThat(names.withheldHash(HASH_A)).as("no marker -> servable").isFalse();
        assertThat(names.withheldHash(HASH_B)).as("marker present -> withheld").isTrue();
    }

    // ---- HIDE_WITHHELD does zero blob-stat I/O -----------------------------------------------------------------

    @Test
    void hide_withheld_membership_policy_stats_no_blob_while_serve_parity_does() throws IOException {
        SpyStore store = new SpyStore();
        store.pointer("publish/maven/g/a/1/served.jar", HASH_A);
        store.blob(HASH_A);
        store.pointer("npm/pkg/tarballs/served.tgz", HASH_A);

        Withholding chain = new Withholding("/maven/g/a/1/withheld.jar");
        ServableNames names = new ServableNames(store, new Publication(store, List.of(chain)));

        // publish-namespace membership: chain probe only, no pointer read and no blob stat.
        store.blobStats = 0;
        assertThat(names.disclosable("/maven/g/a/1/served.jar", Policy.HIDE_WITHHELD)).isTrue();
        assertThat(names.disclosable("/maven/g/a/1/withheld.jar", Policy.HIDE_WITHHELD)).isFalse();
        assertThat(store.blobStats).as("HIDE_WITHHELD on a publish path must stat no blobs/ object").isZero();

        // blobs-namespace membership: pointer + marker read, still no blob stat.
        store.blobStats = 0;
        assertThat(names.disclosableKey("npm/pkg/tarballs/served.tgz", Policy.HIDE_WITHHELD)).isTrue();
        assertThat(store.blobStats).as("HIDE_WITHHELD on a blobs key must stat no blobs/ object").isZero();

        // The spy actually counts: serve-parity DOES stat the blob, proving the zero above is load-bearing.
        store.blobStats = 0;
        assertThat(names.disclosable("/maven/g/a/1/served.jar", Policy.HIDE_WITHHELD_AND_GONE)).isTrue();
        assertThat(store.blobStats).as("HIDE_WITHHELD_AND_GONE is serve-parity and stats the blob").isPositive();
    }

    // ---- hostile-name containment (fail-closed skip, never a 500) ----------------------------------------------

    @Test
    void a_hostile_unresolvable_name_is_skipped_not_thrown() {
        HostileStore store = new HostileStore();
        ServableNames names = new ServableNames(store);

        assertThatCode(() -> {
            assertThat(names.state("/\uD800bad")).isEqualTo(State.WITHHELD);
            assertThat(names.disclosable("/\uD800bad", Policy.HIDE_WITHHELD_AND_GONE)).isFalse();
            assertThat(names.keyState("npm/\uD800bad")).isEqualTo(State.WITHHELD);
            assertThat(names.disclosableKey("npm/\uD800bad", Policy.HIDE_WITHHELD_AND_GONE)).isFalse();
            assertThat(names.disclosableVersionFolder("/maven/\uD800bad")).isFalse();
            assertThat(names.withheldHash("\uD800bad")).isTrue();
        }).as("a name a store backend cannot resolve must skip (undisclosed), never throw out of the seam")
                .doesNotThrowAnyException();
    }

    @Test
    void one_hostile_name_in_a_listing_does_not_500_the_whole_page() {
        HostileStore store = new HostileStore();
        ServableNames names = new ServableNames(store);
        List<String> forwarded = new ArrayList<>();
        // isDirectory itself throws for the hostile child, exercising screening's own containment.
        Consumer<String> screen = names.screening("/maven/g/a", Policy.HIDE_WITHHELD_AND_GONE,
                child -> {
                    if (child.contains("\uD800")) {
                        throw new java.nio.file.InvalidPathException(child, "unmappable");
                    }
                    return false;
                }, forwarded::add);

        assertThatCode(() -> {
            screen.accept("\uD800bad");
            screen.accept("good.jar");
        }).as("a hostile child must be contained, not propagate out of the paged consumer")
                .doesNotThrowAnyException();
        assertThat(forwarded).as("the hostile child is dropped; a resolvable leaf is still screened").isEmpty();
    }

    @Test
    void the_quarantine_root_child_is_always_suppressed_and_directories_forward_unconditionally() {
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/1/served.jar", HASH_A);
        store.blob(HASH_A);
        ServableNames names = new ServableNames(store);
        List<String> forwarded = new ArrayList<>();

        Consumer<String> root = names.screening("", Policy.HIDE_WITHHELD_AND_GONE,
                child -> true /* every root child is a directory */, forwarded::add);
        root.accept(ServableNames.QUARANTINE);
        root.accept("maven");
        assertThat(forwarded).as("the review subtree is never enumerated; a real namespace directory is")
                .containsExactly("maven");
    }

    // ---- disclosableVersionFolder ------------------------------------------------------------------------------

    @Test
    void a_version_folder_held_through_the_quarantine_pointer_convention_is_hidden() throws IOException {
        MapStore store = new MapStore();
        // A held version: the gate diverted its served path to publish/quarantine<path>.
        store.pointer("publish/quarantine/maven/g/a/1/a-1.jar", HASH_A);
        store.pointer("publish/maven/g/a/1/a-1.jar", HASH_A);
        store.blob(HASH_A);
        ServableNames names = new ServableNames(store);

        assertThat(names.disclosableVersionFolder("/maven/g/a/1"))
                .as("a version with a /quarantine review pointer must not be listed").isFalse();
    }

    @Test
    void a_fake_hash_no_blob_version_folder_keeps_listing_because_no_blob_is_ever_stated() throws IOException {
        MapStore store = new MapStore();
        // A version linked to a hash whose blob was never stored (the maven fake-hash test shape): no quarantine.
        store.pointer("publish/maven/g/a/2/a-2.jar", HASH_GONE);
        ServableNames names = new ServableNames(store);        // empty chain

        assertThat(names.disclosableVersionFolder("/maven/g/a/2"))
                .as("a fake-hash / no-blob version must keep listing (membership, not serve-parity)").isTrue();
    }

    @Test
    void an_empty_chain_version_folder_lists_and_a_chain_held_leaf_hides_it() throws IOException {
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/3/a-3.jar", HASH_A);
        store.blob(HASH_A);

        ServableNames open = new ServableNames(store);         // empty chain, no quarantine
        assertThat(open.disclosableVersionFolder("/maven/g/a/3")).as("empty chain -> folder lists").isTrue();

        Withholding chain = new Withholding("/maven/g/a/3/a-3.jar");
        ServableNames held = new ServableNames(store, new Publication(store, List.of(chain)));
        assertThat(held.disclosableVersionFolder("/maven/g/a/3"))
                .as("the chain withholding a leaf hides the whole version folder").isFalse();
    }

    // ---- doubles -----------------------------------------------------------------------------------------------

    /** An interceptor that withholds exactly the request paths it is constructed with - the free chain is empty, so
     *  this is how a test drives the WITHHELD leg without an enterprise compliance gate on the module path. */
    private static final class Withholding implements PublishInterceptor {

        private final Set<String> held;

        private Withholding(String... paths) {
            this.held = Set.of(paths);
        }

        @Override
        public boolean withheld(String path, ArtifactStore store) {
            return held.contains(path);
        }
    }

    /** An in-memory {@link ArtifactStore} over a flat key map: pointers carry their hash as content, blobs and
     *  {@code withheld/} markers are presence-only, and {@link #list} derives immediate children from the key set. */
    private static class MapStore implements ArtifactStore {

        final Map<String, byte[]> objects = new LinkedHashMap<>();

        void pointer(String key, String hash) {
            objects.put(key, hash.getBytes(StandardCharsets.UTF_8));
        }

        void blob(String hash) {
            objects.put("blobs/" + hash, new byte[0]);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public long size(String key) {
            byte[] value = objects.get(key);
            return value == null ? -1L : value.length;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            byte[] value = objects.get(key);
            return value == null ? Optional.empty() : Optional.of(new Versioned(value, value));
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            objects.put(key, content);
            return true;
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            objects.put(key, in.readAllBytes());
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public List<String> list(String prefix) {
            String base = prefix.endsWith("/") ? prefix : prefix + "/";
            Set<String> children = new TreeSet<>();
            for (String key : objects.keySet()) {
                if (key.startsWith(base)) {
                    int slash = key.indexOf('/', base.length());
                    children.add(slash < 0 ? key.substring(base.length()) : key.substring(base.length(), slash));
                }
            }
            return new ArrayList<>(children);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public void read(String key, OutputStream out) {
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(objects.getOrDefault(key, new byte[0]));
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }
    }

    /** A {@link MapStore} that counts every {@code exists}/{@code size} probe of a {@code blobs/} object, so a test can
     *  assert {@link Policy#HIDE_WITHHELD} touched no blob at all. */
    private static final class SpyStore extends MapStore {

        int blobStats;

        @Override
        public boolean exists(String key) {
            if (key.startsWith("blobs/")) {
                blobStats++;
            }
            return super.exists(key);
        }

        @Override
        public long size(String key) {
            if (key.startsWith("blobs/")) {
                blobStats++;
            }
            return super.size(key);
        }
    }

    /** A store backend that cannot resolve a key, exactly as {@code FilesystemArtifactStore.resolve} throws an
     *  {@link java.nio.file.InvalidPathException} on an encoding-hostile name - every probe throws, so the test proves
     *  the seam contains the {@link RuntimeException} rather than letting it escape. */
    private static final class HostileStore extends MapStore {

        private static RuntimeException unresolvable(String key) {
            return new java.nio.file.InvalidPathException(key, "unmappable character");
        }

        @Override
        public boolean exists(String key) {
            throw unresolvable(key);
        }

        @Override
        public long size(String key) {
            throw unresolvable(key);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            throw unresolvable(key);
        }

        @Override
        public List<String> list(String prefix) {
            throw unresolvable(prefix);
        }
    }
}
