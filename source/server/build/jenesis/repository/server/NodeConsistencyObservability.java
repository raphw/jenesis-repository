package build.jenesis.repository.server;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

import module java.base;

/**
 * The observability face of the multi-node consistency check (WCON.2, WO.4): the fleet's per-node numbers reported as
 * self-describing signals, so the same overview that shows every other {@code jenesis.*} signal shows how many nodes are
 * live and whether any is diverged. It reports {@code jenesis.consistency.nodes} (a gauge of live nodes),
 * {@code jenesis.consistency.diverged} (a gauge of stuck divergences), and a {@code jenesis.consistency.divergence}
 * health check that is {@link Health#UP} when the fleet has converged (or is single-node) and {@link Health#DEGRADED}
 * when a node is stuck - a detect-not-block signal, never a failure.
 *
 * <p>These numbers are the very thing that makes the WO.4 "these numbers are instance-specific; warn when multiple
 * nodes" caveat trustworthy: the overview can now say <em>how many</em> instances there are and whether they agree. A
 * single-node deployment reports one node and full health - no false divergence. Reading it is cheap: it runs the same
 * bounded {@link NodeConsistency#report} (the {@code consistency/nodes/} prefix plus one small object per node), never a
 * scan; a store it cannot read reports nothing rather than failing the overview.
 */
public final class NodeConsistencyObservability implements ObservabilitySource {

    private final NodeConsistency check;

    public NodeConsistencyObservability(NodeConsistency check) {
        this.check = Objects.requireNonNull(check, "check");
    }

    @Override
    public List<Metric> metrics() {
        ConsistencyReport report = report();
        if (report == null) {
            return List.of();
        }
        return List.of(
                Metric.gauge("jenesis.consistency.nodes",
                        "Live nodes sharing this store (heartbeating within the staleness window).",
                        report.liveCount(), "nodes"),
                Metric.gauge("jenesis.consistency.diverged",
                        "Nodes flagged stuck-diverged from the fleet (config, cursor or pointer split).",
                        report.divergences().size(), "nodes"));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        ConsistencyReport report = report();
        if (report == null) {
            return List.of();
        }
        if (report.singleNode()) {
            return List.of(new HealthCheck("jenesis.consistency.divergence",
                    "Whether any node has diverged from the fleet (detect-only, never blocks).", Health.UP,
                    "single node - nothing to diverge from"));
        }
        Health health = report.converged() ? Health.UP : Health.DEGRADED;
        String detail = report.converged()
                ? report.liveCount() + " live nodes converged"
                : report.divergences().size() + " of " + report.liveCount() + " live nodes diverged";
        return List.of(new HealthCheck("jenesis.consistency.divergence",
                "Whether any node has diverged from the fleet (detect-only, never blocks).", health, detail));
    }

    /** The current report, or {@code null} when the store cannot be read - the graceful "report nothing" path that
     *  keeps the overview from failing on a transient store error. */
    private ConsistencyReport report() {
        try {
            return check.report(System.currentTimeMillis());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
