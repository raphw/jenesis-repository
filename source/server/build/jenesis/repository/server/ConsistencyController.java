package build.jenesis.repository.server;

import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import module java.base;

/**
 * The multi-node consistency read - {@code GET /api/consistency} (WCON.2), the console / CLI / API read of the fleet's
 * per-node fingerprints and any divergence between them. It runs the same {@link NodeConsistency#report bounded check}
 * (the {@code consistency/nodes/} prefix plus one small object per node, never a scan) and returns the per-node numbers
 * (WO.4), the divergences (each a stuck-cursor / config / pointer split with a value-free reason), and the
 * {@code converged} / {@code singleNode} flags - so a caller sees at a glance whether the nodes agree.
 *
 * <p>Read like every other {@code /api} surface - key-auth'd ({@code repository:read}) by
 * {@link RepositorySecurityAutoConfiguration}, read-only, never an anonymous backdoor and it never blocks a request; it
 * only observes. It <strong>degrades cleanly to single-node</strong>: one live node returns that node and no divergence,
 * a false positive impossible. The enterprise edition mirrors this independently as an operator-gated
 * {@code /api/admin/consistency}. It names the risk (which node, how far behind), never a resolved hash or config value.
 */
@RestController
public final class ConsistencyController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NodeConsistency consistency;
    private final String localNodeId;

    public ConsistencyController(NodeConsistency consistency, String localNodeId) {
        this.consistency = Objects.requireNonNull(consistency, "consistency");
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
    }

    @GetMapping("/api/consistency")
    public void consistency(HttpServletResponse response) throws IOException {
        ConsistencyReport report = consistency.report(System.currentTimeMillis());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ConsistencyReport.NodeView node : report.nodes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeId", node.nodeId());
            row.put("live", node.live());
            row.put("stale", node.stale());
            row.put("heartbeatAgeMillis", node.heartbeatAgeMillis());
            row.put("indexCursor", node.indexCursor());
            row.put("snapshotVersion", node.snapshotVersion());
            row.put("configGeneration", Long.toHexString(node.configGeneration()));
            row.put("inventoryTotal", node.inventoryTotal());
            row.put("quotaUsed", node.quotaUsed());
            row.put("local", node.nodeId().equals(localNodeId));
            nodes.add(row);
        }

        List<Map<String, Object>> divergences = new ArrayList<>();
        for (NodeDivergence divergence : report.divergences()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeId", divergence.nodeId());
            row.put("kind", divergence.kind().name());
            row.put("detail", divergence.detail());
            divergences.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("localNodeId", localNodeId);
        body.put("nodeCount", report.nodes().size());
        body.put("liveCount", report.liveCount());
        body.put("converged", report.converged());
        body.put("singleNode", report.singleNode());
        body.put("nodes", nodes);
        body.put("divergences", divergences);

        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
