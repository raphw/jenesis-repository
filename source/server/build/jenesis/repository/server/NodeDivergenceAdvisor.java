package build.jenesis.repository.server;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import module java.base;

/**
 * The WCON.2 "node divergence" security-posture advisor (WO.5): it reads every node's published
 * {@link NodeFingerprint} from the shared store, runs the {@link ConsistencyReport consistency check}, and raises one
 * advisory per {@link NodeDivergence stuck divergence} - each with the <em>why</em> (which node, how far behind, for how
 * long) and the fix (check the sweep lease, reconcile the config), so a wedged node surfaces on the same
 * {@code GET /api/posture} / console / boot-log posture surfaces every other configuration warning does. It is
 * {@code provides}-declared, discovered with {@link java.util.ServiceLoader} like the core {@code SecurityPosture}
 * seed, and reads its store from the effective configuration exactly as the deployment resolves it.
 *
 * <p>It <strong>degrades cleanly</strong>: a single-node deployment (or one whose nodes agree) reports nothing, so the
 * posture surface never advises about divergence that is not happening; and a store it cannot read (misconfigured,
 * absent, or a transient I/O error) yields no advisory rather than a failure - observing consistency never blocks. The
 * advisory names the risk, never a resolved hash or a config value, so this surface cannot leak one. The read is cheap
 * (the {@code consistency/nodes/} prefix plus one small object per node), never a scan.
 */
public final class NodeDivergenceAdvisor implements SafetyAdvisor {

    static final String DOCS = "https://jenesis.build/docs/observability/consistency";

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        ConsistencyReport report;
        try {
            String backend = config.optional("jenesis.repository.store").orElse("filesystem");
            ArtifactStore store = ArtifactStoreProvider.resolve(backend, config::value);
            NodeConsistency check = new NodeConsistency(store, NodeConsistency.settingsFrom(config::value));
            report = check.report(System.currentTimeMillis());
        } catch (RuntimeException unavailable) {
            // The store is not resolvable here (or a transient read failure) - the consistency surface degrades to
            // nothing rather than turning a posture read into an error. The dedicated /api/consistency surface, wired
            // with the live store, remains the authoritative read.
            return List.of();
        }
        if (report.converged()) {
            return List.of();
        }
        List<SecurityAdvisory> advisories = new ArrayList<>();
        for (NodeDivergence divergence : report.divergences()) {
            advisories.add(advisory(divergence));
        }
        return advisories;
    }

    /** Map one divergence to its posture advisory: a stuck cursor is an operational WARN (fix the sweep), while a
     *  config or pointer split is a CRITICAL - the fleet disagrees on what must be identical. */
    public static SecurityAdvisory advisory(NodeDivergence divergence) {
        return switch (divergence.kind()) {
            case STUCK_CURSOR -> SecurityAdvisory.deployment("jenesis.consistency.stuck", Severity.WARN,
                    "Node " + divergence.nodeId() + " is stuck behind the fleet",
                    "Node " + divergence.nodeId() + " " + divergence.detail() + ". It is alive (still heartbeating) but "
                            + "its derived-index cursor has not advanced past the configured sweep-interval budget, so "
                            + "it is serving a stale view rather than merely lagging.",
                    "Check that node's index-sweep lease and worker: a wedged sweep or a lost lease keeps it from "
                            + "converging. Widen jenesis.consistency.sweep-intervals only if the fleet legitimately "
                            + "needs longer to catch up.",
                    "jenesis.consistency.sweep-intervals", "3", DOCS + "#jenesis.consistency.stuck");
            case CONFIG_MISMATCH -> SecurityAdvisory.deployment("jenesis.consistency.config", Severity.CRITICAL,
                    "Node " + divergence.nodeId() + " disagrees on the deployment config",
                    "Node " + divergence.nodeId() + " " + divergence.detail() + ". Config must be identical on every "
                            + "node; a live node on a different generation missed a config change or is split from the "
                            + "others, so it enforces different settings than the fleet.",
                    "Reconcile this node's configuration with the fleet and confirm it reloaded - restart it if it did "
                            + "not pick up the change. A persistent split points at a lost lease or a partitioned store.",
                    "", "", DOCS + "#jenesis.consistency.config");
            case POINTER_MISMATCH -> SecurityAdvisory.deployment("jenesis.consistency.pointer", Severity.CRITICAL,
                    "Node " + divergence.nodeId() + " resolves a pointer differently",
                    "Node " + divergence.nodeId() + " " + divergence.detail() + ". Two live nodes resolving the same "
                            + "pointer to different content is a split-brain resolution - a client gets different bytes "
                            + "depending on which node answers.",
                    "Investigate this node's derived-index snapshot and cache: force a re-sweep or restart it so it "
                            + "reconverges on the shared store's authoritative resolution.",
                    "", "", DOCS + "#jenesis.consistency.pointer");
        };
    }
}
