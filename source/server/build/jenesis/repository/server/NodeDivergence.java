package build.jenesis.repository.server;

import module java.base;

/**
 * One finding from the multi-node consistency check (WCON.2): a live node that is <em>stuck diverged</em> rather than
 * benignly lagging. It names the node, the {@link Kind} of divergence and a plain, value-free {@link #detail} an
 * operator can act on. The {@link NodeDivergenceAdvisor} turns each of these into a WO.5 security-posture advisory with
 * the <em>why</em> and the fix; the admin API and console page render them directly. It carries no configuration value
 * or resolved hash - a divergence names the risk (which node, how far behind, for how long), never a secret.
 */
public record NodeDivergence(String nodeId, Kind kind, String detail) {

    public NodeDivergence {
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        kind = Objects.requireNonNull(kind, "kind");
        detail = Objects.requireNonNull(detail, "detail");
    }

    /** What kind of divergence this is - each maps to a distinct advisory signal and severity. */
    public enum Kind {
        /** The node is alive but its derived-index cursor has been frozen past the sweep-interval budget while it lags
         *  the fleet - a wedged sweep or a lost lease. Serious but operational (WARN). */
        STUCK_CURSOR,
        /** The node disagrees with the fleet on the config generation - it missed a config change or is split-brained.
         *  Config must be identical, so this is a governance-level divergence (CRITICAL). */
        CONFIG_MISMATCH,
        /** Two live nodes resolve the same pointer to different content - the sharpest disagreement on what must be
         *  identical (CRITICAL). */
        POINTER_MISMATCH
    }

    public static NodeDivergence stuck(String nodeId, long cursorLag, long stalledForMillis) {
        long minutes = Duration.ofMillis(stalledForMillis).toMinutes();
        return new NodeDivergence(nodeId, Kind.STUCK_CURSOR, "index cursor stuck " + cursorLag + " behind the fleet, "
                + "not advanced for " + minutes + " min - check this node's sweep lease");
    }

    public static NodeDivergence config(String nodeId, long generation, long referenceGeneration) {
        return new NodeDivergence(nodeId, Kind.CONFIG_MISMATCH, "config generation " + Long.toHexString(generation)
                + " differs from the fleet's " + Long.toHexString(referenceGeneration)
                + " - this node missed a config change or is split from the others");
    }

    public static NodeDivergence pointer(String nodeId, String pointer) {
        return new NodeDivergence(nodeId, Kind.POINTER_MISMATCH, "resolves pointer '" + pointer
                + "' to different content than the fleet - a split-brain resolution");
    }
}
