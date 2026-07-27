package build.jenesis.repository.test;

import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import walk is an ingress edge: it screens each asset against its target-layout coordinate before the demoted,
 * layout-only importer lays it out (EPIC 26). This re-establishes, now at the edge, the screening coverage the raw
 * importer carried before its embedded gate was removed. It drives {@link RepositoryImport#run} over a fake
 * {@link ImportSource} of raw assets and the real discovered chain - the {@link MarkerInterceptor} that quarantines a
 * {@code gate-quarantine} path and rejects a {@code gate-reject} one - so a held, a rejected and an accepted asset are
 * routed off the same gate a deploy or batch upload passes, with zero compliance module on the path.
 *
 * <ul>
 *   <li>a held asset is diverted to {@code /quarantine<target-path>} (never the served {@code /raw/} path), is not laid
 *       out by the importer, and is counted {@code held};</li>
 *   <li>a rejected asset lays out nothing and is counted {@code rejected}, and the walk continues to the next asset;</li>
 *   <li>an accepted asset is laid out from the restreamed screened blob, counted {@code imported}, and fires
 *       {@link Publication#published} (a {@link RecordingObserver} sees its accepted identity).</li>
 * </ul>
 */
public class ImportEdgeScreeningTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    /** A fake source that hands a fixed list of raw assets to the walk, then reports a single end-of-walk cursor. */
    private static final class FakeSource implements ImportSource {

        private final Map<String, byte[]> assets;

        private FakeSource(Map<String, byte[]> assets) {
            this.assets = assets;
        }

        @Override
        public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
            for (Map.Entry<String, byte[]> asset : assets.entrySet()) {
                consumer.accept("raw", asset.getKey(), () -> new ByteArrayInputStream(asset.getValue()));
            }
            checkpoint.reached(null);
        }
    }

    /** Records which source paths the edge reported held or rejected, and the descriptor/hash it carried. */
    private static final class RecordingListener implements RepositoryImport.Listener {

        private final List<String> imported = new ArrayList<>();
        private final List<String> held = new ArrayList<>();
        private final List<String> rejected = new ArrayList<>();
        private String heldHash;

        @Override
        public void imported(String path) {
            imported.add(path);
        }

        @Override
        public void held(String path, ArtifactDescriptor descriptor, String hash) {
            held.add(path);
            heldHash = hash;
        }

        @Override
        public void rejected(String path, ArtifactDescriptor descriptor) {
            rejected.add(path);
        }
    }

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        RecordingObserver.reset();
    }

    @Test
    void the_edge_holds_rejects_and_accepts_and_the_walk_never_aborts() throws IOException {
        // Ordered so the accepted asset follows the held and rejected ones: its import proves the walk continued past
        // a screened-out asset rather than aborting the migration.
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("gate-quarantine/held.bin", "held-bytes".getBytes(StandardCharsets.UTF_8));
        assets.put("gate-reject/bad.bin", "bad-bytes".getBytes(StandardCharsets.UTF_8));
        byte[] accepted = "accepted-bytes".getBytes(StandardCharsets.UTF_8);
        assets.put("publish-observed/ok.bin", accepted);

        RecordingListener listener = new RecordingListener();
        RepositoryImport.Result result = new RepositoryImport().run(new FakeSource(assets), store, listener);

        assertThat(result.imported()).as("only the accepted asset is imported").isEqualTo(1);
        assertThat(result.held()).as("the quarantined asset is held").isEqualTo(1);
        assertThat(result.rejected()).as("the rejected asset is rejected").isEqualTo(1);
        assertThat(result.skipped()).isZero();

        Publication publication = new Publication(store);

        // Held: diverted to /quarantine<target-path>, never laid out at the served /raw/ path.
        assertThat(publication.blob("/quarantine/raw/gate-quarantine/held.bin"))
                .as("the held asset is linked under the quarantine view").isPresent();
        assertThat(publication.located("/raw/gate-quarantine/held.bin"))
                .as("a held asset is not laid out on the served path").isEmpty();
        assertThat(listener.held).containsExactly("gate-quarantine/held.bin");

        // Rejected: nothing laid out anywhere, and the listener saw it.
        assertThat(publication.located("/raw/gate-reject/bad.bin"))
                .as("a rejected asset lays out nothing").isEmpty();
        assertThat(publication.blob("/quarantine/raw/gate-reject/bad.bin"))
                .as("a rejected asset is not even quarantined").isEmpty();
        assertThat(listener.rejected).containsExactly("gate-reject/bad.bin");

        // Accepted: laid out from the restreamed screened blob and served, counted imported, and published() fired.
        String key = publication.located("/raw/publish-observed/ok.bin").orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read(key, out);
        assertThat(out.toByteArray()).as("the accepted asset serves the screened bytes").isEqualTo(accepted);
        assertThat(listener.imported).containsExactly("publish-observed/ok.bin");
        assertThat(RecordingObserver.published())
                .as("an accepted import fires published() with the accepted blob's identity")
                .hasSize(1);
        ArtifactDescriptor observed = RecordingObserver.published().getFirst();
        assertThat(observed.path()).isEqualTo("/raw/publish-observed/ok.bin");
        assertThat(observed.hash()).as("the published descriptor carries the screened blob's hash").isNotNull();
        assertThat(observed.size()).isEqualTo(accepted.length);
    }
}
