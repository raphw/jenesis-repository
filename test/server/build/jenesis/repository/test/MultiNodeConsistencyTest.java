package build.jenesis.repository.test;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.server.ConsistencyReport;
import build.jenesis.repository.server.NodeConsistency;
import build.jenesis.repository.server.NodeConsistencyObservability;
import build.jenesis.repository.server.NodeDivergence;
import build.jenesis.repository.server.NodeDivergenceAdvisor;
import build.jenesis.repository.server.NodeFingerprint;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The multi-node consistency check (WCON.2, free core): two in-process nodes over one shared store agree (no
 * divergence); stalling one node's cursor flags it <em>stuck</em>, not benign lag, respecting the staleness window; a
 * config-generation mismatch is flagged; a single node degrades to one node with no false positive; the fingerprint
 * read is cheap (it lists only the {@code consistency/nodes/} prefix and reads one small object per node - it never
 * scans the blob namespace, proven with a read/list counter that stays bounded no matter how many blobs exist). It also
 * pins the divergence advisory's severities (WO.5) and the observability health (WO.4). The {@code GET /api/consistency}
 * authorization gating and the console panel are pinned end to end by {@link MultiNodeConsistencyE2ETest}.
 */
class MultiNodeConsistencyTest {

    private static final ConsistencyReport.Settings SETTINGS = ConsistencyReport.Settings.defaults();
    private static final long NOW = 1_000_000_000_000L;

    private static NodeConsistency over(ArtifactStore store) {
        return new NodeConsistency(store, SETTINGS);
    }

    private static ArtifactStore filesystem(Path root) {
        return ArtifactStoreProvider.resolve("filesystem", key -> "JENESIS_STORE_ROOT".equals(key)
                ? root.toString() : null);
    }

    /** A fingerprint that is fresh (heartbeat and cursor-advance at {@code NOW}) - a converged, up-to-date node. */
    private static NodeFingerprint fresh(String id, long cursor, long generation) {
        return new NodeFingerprint(id, NOW, NOW, cursor, "", generation, 0L, 0L, Map.of());
    }

    @Test
    void two_nodes_that_agree_show_no_divergence(@TempDir Path root) throws IOException {
        ArtifactStore store = filesystem(root);
        long generation = NodeFingerprint.configGeneration(Map.of("jenesis.repository.store", "filesystem"));
        over(store).publish(fresh("node-a", 100, generation));
        over(store).publish(fresh("node-b", 100, generation));

        ConsistencyReport report = over(store).report(NOW);
        assertThat(report.liveCount()).as("both nodes are live").isEqualTo(2);
        assertThat(report.singleNode()).isFalse();
        assertThat(report.converged()).as("agreeing nodes do not diverge").isTrue();
        assertThat(report.divergences()).isEmpty();
    }

    @Test
    void a_stalled_cursor_is_flagged_stuck_not_benign_lag(@TempDir Path root) throws IOException {
        ArtifactStore store = filesystem(root);
        long generation = NodeFingerprint.configGeneration(Map.of("k", "v"));
        // node-a is furthest advanced; node-b lags AND its cursor has been frozen past the sweep-interval budget.
        over(store).publish(fresh("node-a", 500, generation));
        long stalledPast = NOW - (SETTINGS.stuckAfterMillis() + 1);
        over(store).publish(new NodeFingerprint("node-b", NOW, stalledPast, 100, "", generation, 0L, 0L, Map.of()));

        ConsistencyReport report = over(store).report(NOW);
        assertThat(report.converged()).as("a stuck node diverges").isFalse();
        assertThat(report.divergences()).singleElement()
                .satisfies(divergence -> {
                    assertThat(divergence.nodeId()).isEqualTo("node-b");
                    assertThat(divergence.kind()).isEqualTo(NodeDivergence.Kind.STUCK_CURSOR);
                });
    }

    @Test
    void a_lagging_but_advancing_node_within_the_window_is_benign(@TempDir Path root) throws IOException {
        ArtifactStore store = filesystem(root);
        long generation = NodeFingerprint.configGeneration(Map.of("k", "v"));
        over(store).publish(fresh("node-a", 500, generation));
        // node-b lags on cursor but its cursor advanced recently (within the staleness window) - benign lag, not stuck.
        long advancedRecently = NOW - (SETTINGS.stuckAfterMillis() / 2);
        over(store).publish(new NodeFingerprint("node-b", NOW, advancedRecently, 100, "", generation, 0L, 0L, Map.of()));

        ConsistencyReport report = over(store).report(NOW);
        assertThat(report.converged()).as("benign lag within the window is not a divergence").isTrue();
        assertThat(report.divergences()).isEmpty();
    }

    @Test
    void a_cursor_stalled_exactly_at_the_boundary_is_benign_not_stuck(@TempDir Path root) throws IOException {
        // The stuck test is strict: stalledFor > stuckAfterMillis. The exact-equality edge (stalledFor ==
        // stuckAfterMillis) is the boundary the benign-lag classification hinges on and must fall on the benign side -
        // a node stalled for exactly the budget has not yet overrun it, so it is lag, not a wedged sweep.
        ArtifactStore store = filesystem(root);
        long generation = NodeFingerprint.configGeneration(Map.of("k", "v"));
        over(store).publish(fresh("node-a", 500, generation));
        long stalledExactly = NOW - SETTINGS.stuckAfterMillis();   // stalledFor == stuckAfterMillis, not strictly over
        over(store).publish(new NodeFingerprint("node-b", NOW, stalledExactly, 100, "", generation, 0L, 0L, Map.of()));

        ConsistencyReport report = over(store).report(NOW);
        assertThat(report.converged()).as("a stall of exactly the budget is benign lag, not stuck").isTrue();
        assertThat(report.divergences()).isEmpty();
    }

    @Test
    void two_live_nodes_resolving_a_pointer_differently_are_flagged(@TempDir Path root) throws IOException {
        // The POINTER_MISMATCH detection in analyze(): a sampled pointer that two live nodes resolve to different
        // content is a critical split (the fleet serves different bytes at one path). Reported once against the node
        // whose resolution differs from the freshest live node's - so node-a (the fresher heartbeat) is the reference.
        ArtifactStore store = filesystem(root);
        long generation = NodeFingerprint.configGeneration(Map.of("k", "v"));
        String hashA = "a".repeat(64);
        String hashB = "b".repeat(64);
        over(store).publish(new NodeFingerprint("node-a", NOW, NOW, 100, "", generation, 0L, 0L,
                Map.of("publish/raw/p", hashA)));
        // node-b is live but its heartbeat is a touch older, so node-a is the reference; it resolves the shared pointer
        // to different content.
        over(store).publish(new NodeFingerprint("node-b", NOW - 1000, NOW - 1000, 100, "", generation, 0L, 0L,
                Map.of("publish/raw/p", hashB)));

        ConsistencyReport report = over(store).report(NOW);
        assertThat(report.converged()).as("a pointer split diverges the fleet").isFalse();
        assertThat(report.divergences()).singleElement().satisfies(divergence -> {
            assertThat(divergence.kind()).isEqualTo(NodeDivergence.Kind.POINTER_MISMATCH);
            assertThat(divergence.nodeId()).as("reported against the node differing from the reference").isEqualTo("node-b");
            assertThat(divergence.detail()).contains("publish/raw/p");
        });
    }

    @Test
    void a_config_generation_mismatch_is_flagged(@TempDir Path root) throws IOException {
        ArtifactStore store = filesystem(root);
        long generationA = NodeFingerprint.configGeneration(Map.of("jenesis.repository.read-only", "false"));
        long generationB = NodeFingerprint.configGeneration(Map.of("jenesis.repository.read-only", "true"));
        over(store).publish(fresh("node-a", 100, generationA));
        over(store).publish(fresh("node-b", 100, generationB));

        ConsistencyReport report = over(store).report(NOW);
        assertThat(report.converged()).isFalse();
        assertThat(report.divergences()).anySatisfy(divergence ->
                assertThat(divergence.kind()).isEqualTo(NodeDivergence.Kind.CONFIG_MISMATCH));
    }

    @Test
    void a_single_node_degrades_with_no_false_positive(@TempDir Path root) throws IOException {
        ArtifactStore store = filesystem(root);
        over(store).publish(fresh("only-node", 42, 7L));

        ConsistencyReport report = over(store).report(NOW);
        assertThat(report.singleNode()).as("one live node - nothing to compare").isTrue();
        assertThat(report.liveCount()).isEqualTo(1);
        assertThat(report.converged()).as("a single node never diverges").isTrue();
        assertThat(report.divergences()).isEmpty();
        assertThat(report.nodes()).singleElement()
                .satisfies(node -> assertThat(node.nodeId()).isEqualTo("only-node"));
    }

    @Test
    void a_dead_node_drops_out_of_the_live_comparison(@TempDir Path root) throws IOException {
        ArtifactStore store = filesystem(root);
        over(store).publish(fresh("live-node", 100, 1L));
        // A node last heard from long past the dead-after window is reported but never marks the fleet diverged.
        long longAgo = NOW - (SETTINGS.deadAfterMillis() + Duration.ofMinutes(10).toMillis());
        over(store).publish(new NodeFingerprint("dead-node", longAgo, longAgo, 0, "", 999L, 0L, 0L, Map.of()));

        ConsistencyReport report = over(store).report(NOW);
        assertThat(report.liveCount()).as("only the live node counts").isEqualTo(1);
        assertThat(report.singleNode()).isTrue();
        assertThat(report.converged()).as("a dead, disagreeing node is not a divergence").isTrue();
        assertThat(report.nodes()).hasSize(2);
    }

    @Test
    void the_fingerprint_read_is_cheap_and_never_scans_the_store(@TempDir Path root) throws IOException {
        CountingStore counting = new CountingStore(filesystem(root));
        // Seed a large blob namespace: a full scan would touch all of these. The check must not.
        for (int i = 0; i < 500; i++) {
            counting.writeVersioned("blobs/blob-" + i, new byte[] {1}, null);
        }
        over(counting).publish(fresh("node-a", 1, 1L));
        over(counting).publish(fresh("node-b", 1, 1L));
        counting.reset();

        ConsistencyReport report = over(counting).report(NOW);

        assertThat(report.liveCount()).isEqualTo(2);
        assertThat(counting.listCalls).as("exactly one list of the node prefix, never the blob namespace").isEqualTo(1);
        assertThat(counting.versionedReads).as("one small read per node, bounded by node count not store size")
                .isEqualTo(2);
        assertThat(counting.blobReads).as("no blob is ever opened - not a scan").isZero();
    }

    @Test
    void the_divergence_advisor_maps_severities_and_ids() {
        assertThat(NodeDivergenceAdvisor.advisory(NodeDivergence.stuck("node-x", 40, 2_400_000)))
                .satisfies(advisory -> {
                    assertThat(advisory.id()).isEqualTo("jenesis.consistency.stuck");
                    assertThat(advisory.severity()).isEqualTo(Severity.WARN);
                    assertThat(advisory.why()).contains("node-x").doesNotContain("secret");
                });
        assertThat(NodeDivergenceAdvisor.advisory(NodeDivergence.config("node-y", 1L, 2L)).severity())
                .as("config split is critical - the fleet disagrees on what must be identical").isEqualTo(Severity.CRITICAL);
        assertThat(NodeDivergenceAdvisor.advisory(NodeDivergence.pointer("node-z", "publish/p")).severity())
                .isEqualTo(Severity.CRITICAL);
    }

    @Test
    void the_advisor_surfaces_a_live_config_split_and_is_silent_for_a_single_node(@TempDir Path root)
            throws IOException {
        ArtifactStore store = filesystem(root);
        Configuration config = Configuration.ofMap(Map.of(
                "jenesis.repository.store", "filesystem", "JENESIS_STORE_ROOT", root.toString()));

        over(store).publish(new NodeFingerprint("node-a", System.currentTimeMillis(), System.currentTimeMillis(),
                100, "", 1L, 0L, 0L, Map.of()));
        assertThat(new NodeDivergenceAdvisor().advise(config))
                .as("a single node raises no divergence advisory").isEmpty();

        over(store).publish(new NodeFingerprint("node-b", System.currentTimeMillis(), System.currentTimeMillis(),
                100, "", 2L, 0L, 0L, Map.of()));
        assertThat(new NodeDivergenceAdvisor().advise(config))
                .as("two live nodes on different config generations surface a critical advisory")
                .anySatisfy(advisory -> {
                    assertThat(advisory.id()).isEqualTo("jenesis.consistency.config");
                    assertThat(advisory.severity()).isEqualTo(Severity.CRITICAL);
                });
    }

    @Test
    void the_observability_reports_node_counts_and_divergence_health(@TempDir Path root) throws IOException {
        ArtifactStore store = filesystem(root);
        NodeConsistencyObservability single = new NodeConsistencyObservability(over(store));
        over(store).publish(new NodeFingerprint("solo", System.currentTimeMillis(), System.currentTimeMillis(),
                1, "", 1L, 0L, 0L, Map.of()));
        assertThat(single.healthChecks()).singleElement()
                .satisfies(check -> assertThat(check.status()).as("single node is healthy").isEqualTo(Health.UP));

        // Add a diverging second live node: the divergence health degrades (detect-only, never a failure).
        over(store).publish(new NodeFingerprint("split", System.currentTimeMillis(), System.currentTimeMillis(),
                1, "", 2L, 0L, 0L, Map.of()));
        NodeConsistencyObservability fleet = new NodeConsistencyObservability(over(store));
        assertThat(fleet.healthChecks()).extracting(HealthCheck::status).containsExactly(Health.DEGRADED);
        assertThat(fleet.metrics()).extracting(Metric::name)
                .contains("jenesis.consistency.nodes", "jenesis.consistency.diverged");
    }

    /** An {@link ArtifactStore} decorator that counts the reads a consistency check makes, so a test can prove the
     *  check lists only the node prefix and opens no blob - it is bounded by node count, never a store scan. */
    private static final class CountingStore implements ArtifactStore {

        private final ArtifactStore delegate;
        int listCalls;
        int versionedReads;
        int blobReads;

        CountingStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        void reset() {
            listCalls = 0;
            versionedReads = 0;
            blobReads = 0;
        }

        @Override
        public List<String> list(String prefix) {
            listCalls++;
            return delegate.list(prefix);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            versionedReads++;
            return delegate.readVersioned(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            blobReads++;
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            blobReads++;
            return delegate.open(key);
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
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
