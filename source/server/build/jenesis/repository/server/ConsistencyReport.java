package build.jenesis.repository.server;

import module java.base;

/**
 * The single collected view of a multi-node consistency check (WCON.2): the {@link NodeView per-node numbers} every
 * live node published and the {@link NodeDivergence divergences} the comparison found. {@link #analyze} is the pure
 * function at the heart of the feature - it takes the fingerprints read from the shared store, a wall-clock {@code now}
 * and the {@link Settings} (the staleness window and the sweep-interval budget) and classifies the fleet, so the
 * admin API, the console page, the {@link NodeDivergenceAdvisor divergence advisory} and the
 * {@link NodeConsistencyObservability per-node metrics} all render <em>this</em> - one classification, many surfaces.
 *
 * <p>It <strong>detects and reports, never blocks</strong> - matching the eventual-consistency contract - and it
 * <strong>degrades cleanly to single-node</strong>: with one live node (or none) there is nothing to disagree with, so
 * it reports that node and no divergence, never a false positive. The rule that gives the feature its value is the
 * distinction between <em>benign lag</em> (a node a little behind the fleet's cursor but still advancing, within the
 * staleness window) and <em>stuck divergence</em> (a node alive - still heartbeating - but whose cursor has not moved
 * for more than {@code N} sweep intervals while it lags, or that disagrees on what must be identical: the config
 * generation, or a sampled pointer's resolution). A benign lagger is not flagged; a stuck one is.
 */
public record ConsistencyReport(List<NodeView> nodes, List<NodeDivergence> divergences, int liveCount) {

    public ConsistencyReport {
        nodes = List.copyOf(nodes);
        divergences = List.copyOf(divergences);
    }

    /** Whether the fleet is consistent - no divergence found (an empty divergence list is the healthy state). */
    public boolean converged() {
        return divergences.isEmpty();
    }

    /** Whether this is effectively a single-node deployment - at most one live node, so there is nothing to compare and
     *  no divergence is possible (the graceful single-node degradation). */
    public boolean singleNode() {
        return liveCount <= 1;
    }

    /** The tuning a check runs under, each grounded in a real {@code jenesis.consistency.*} setting an operator sets.
     *  {@code stalenessWindowMillis} is how far behind the fleet cursor a node may fall and still be benign lag;
     *  {@code sweepIntervalMillis} times {@code sweepIntervals} is the budget a lagging node has to advance before it is
     *  called stuck; {@code deadAfterMillis} is when a silent node drops out of the live comparison entirely. */
    public record Settings(long stalenessWindowMillis, long sweepIntervalMillis, int sweepIntervals,
                           long deadAfterMillis) {

        /** The documented defaults: a 5-minute staleness window, a 60-second sweep interval, 3 intervals to catch up
         *  before stuck, and a 15-minute silence before a node is considered dead. */
        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(5).toMillis(), Duration.ofSeconds(60).toMillis(), 3,
                    Duration.ofMinutes(15).toMillis());
        }

        /** The wall-clock budget a lagging node has to advance its cursor before it is judged stuck. */
        public long stuckAfterMillis() {
            return sweepIntervalMillis * Math.max(1, sweepIntervals);
        }
    }

    /**
     * Classify a set of fingerprints into a report. Only <em>live</em> nodes (heartbeat within
     * {@link Settings#deadAfterMillis}) take part in the comparison - a dead node is reported for visibility but never
     * marks the fleet diverged, and a fleet of one live node is always converged (single-node degradation). The rules:
     *
     * <ul>
     *   <li><strong>Config generation</strong> must be identical across live nodes; the reference is the freshest live
     *       node's generation, and any live node disagreeing is a <em>config</em> divergence (a missed config change /
     *       split), never treated as lag.</li>
     *   <li><strong>Index cursor</strong>: the reference is the furthest-advanced live cursor. A node behind it is
     *       benign lag while its cursor is still advancing (frozen for no more than {@link Settings#stuckAfterMillis});
     *       once its cursor has been frozen longer than that while it still lags, it is a <em>stuck</em> divergence.</li>
     *   <li><strong>Pointer resolutions</strong>: two live nodes resolving the same sampled pointer to different content
     *       is a <em>pointer</em> divergence - the sharpest disagreement on what must be identical.</li>
     * </ul>
     */
    public static ConsistencyReport analyze(Collection<NodeFingerprint> fingerprints, long now, Settings settings) {
        List<NodeFingerprint> sorted = new ArrayList<>(fingerprints);
        sorted.sort(Comparator.comparing(NodeFingerprint::nodeId));

        List<NodeFingerprint> live = new ArrayList<>();
        for (NodeFingerprint fingerprint : sorted) {
            if (fingerprint.heartbeatAgeMillis(now) <= settings.deadAfterMillis()) {
                live.add(fingerprint);
            }
        }

        List<NodeDivergence> divergences = new ArrayList<>();
        if (live.size() > 1) {
            // The freshest live node is the reference for config generation (it has the most recent view of a config
            // change); the furthest-advanced live cursor is the reference for progress.
            NodeFingerprint freshest = live.stream()
                    .max(Comparator.comparingLong(NodeFingerprint::heartbeatMillis)).orElseThrow();
            long referenceGeneration = freshest.configGeneration();
            long referenceCursor = live.stream().mapToLong(NodeFingerprint::indexCursor).max().orElse(0);

            for (NodeFingerprint node : live) {
                if (node.configGeneration() != referenceGeneration) {
                    divergences.add(NodeDivergence.config(node.nodeId(), node.configGeneration(), referenceGeneration));
                }
                if (node.indexCursor() < referenceCursor) {
                    long lag = referenceCursor - node.indexCursor();
                    long stalledFor = node.stalledForMillis(now);
                    if (stalledFor > settings.stuckAfterMillis()) {
                        divergences.add(NodeDivergence.stuck(node.nodeId(), lag, stalledFor));
                    }
                    // else: benign lag - still within the staleness window / catch-up budget, not flagged.
                }
            }
            divergences.addAll(pointerDivergences(live));
        }

        List<NodeView> views = new ArrayList<>();
        for (NodeFingerprint node : sorted) {
            boolean nodeLive = node.heartbeatAgeMillis(now) <= settings.deadAfterMillis();
            boolean stale = node.heartbeatAgeMillis(now) > settings.stalenessWindowMillis();
            views.add(new NodeView(node.nodeId(), node.heartbeatAgeMillis(now), nodeLive, stale, node.indexCursor(),
                    node.snapshotVersion(), node.configGeneration(), node.inventoryTotal(), node.quotaUsed()));
        }
        return new ConsistencyReport(views, divergences, live.size());
    }

    /** Any sampled pointer that two live nodes resolve to different content - one divergence per disagreeing key,
     *  reported once against the node whose resolution differs from the freshest live node's. */
    private static List<NodeDivergence> pointerDivergences(List<NodeFingerprint> live) {
        NodeFingerprint reference = live.stream()
                .max(Comparator.comparingLong(NodeFingerprint::heartbeatMillis)).orElseThrow();
        List<NodeDivergence> found = new ArrayList<>();
        for (NodeFingerprint node : live) {
            if (node == reference) {
                continue;
            }
            for (Map.Entry<String, String> entry : node.pointers().entrySet()) {
                String referenceHash = reference.pointers().get(entry.getKey());
                if (referenceHash != null && !referenceHash.equals(entry.getValue())) {
                    found.add(NodeDivergence.pointer(node.nodeId(), entry.getKey()));
                }
            }
        }
        found.sort(Comparator.comparing(NodeDivergence::nodeId).thenComparing(NodeDivergence::detail));
        return found;
    }

    /** One node's published numbers, for the WO.4 per-node view - what the console page and the metrics render. */
    public record NodeView(String nodeId, long heartbeatAgeMillis, boolean live, boolean stale, long indexCursor,
                           String snapshotVersion, long configGeneration, long inventoryTotal, long quotaUsed) {
    }
}
