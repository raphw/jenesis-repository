package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.ConsistencyPanel;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The multi-node consistency panel (WCON.2, WO.4): the console's window onto the server's {@code GET /api/consistency}
 * read. Like the recent-logs panel it is a thin, key-gated live-API panel that reads nothing from the store, so it
 * renders without a live fleet - and it carries an explicit <strong>single-node</strong> path so a lone deployment
 * shows one node and no divergence rather than a false positive. All dynamic text is escaped in JS before it reaches
 * the DOM.
 */
class ConsistencyPanelTest {

    @Test
    void it_identifies_itself_as_the_consistency_panel() {
        ConsistencyPanel panel = new ConsistencyPanel();
        assertThat(panel.id()).isEqualTo("consistency");
        assertThat(panel.title()).isEqualTo("Consistency");
    }

    @Test
    void it_renders_a_key_gated_live_read_that_degrades_to_single_node() {
        // The panel reads no artifact data, so a null store is fine here (a live-API panel, like the logs panel).
        String body = new ConsistencyPanel().render(null);

        assertThat(body).as("it reads the fleet from the consistency API").contains("/api/consistency");
        assertThat(body).as("the read is key-gated like every other /api surface")
                .contains("Jenesis-Repository-Key").contains("repository:read");
        assertThat(body).as("it degrades cleanly to single-node").contains("Single node");
        assertThat(body).as("it names the detect-not-block contract").contains("stuck diverged");
    }

    @Test
    void every_api_derived_field_reaches_the_dom_through_an_html_escaper() {
        // Stronger than substring-checking the bare identifier "jconEsc": prove the escaper actually neutralises the
        // three markup metacharacters, and that the untrusted, API-derived fields a malicious node would control (the
        // node id and each divergence's kind/detail/reason) are passed THROUGH that escaper before they are
        // interpolated into innerHTML - so a node id like <script> or a crafted divergence reason cannot inject markup.
        // (A full JS-execution assertion is not possible here: no script engine is on the module path in this JDK.)
        String body = new ConsistencyPanel().render(null);

        assertThat(body).as("the escaper maps & < > to their HTML entities")
                .contains("replace(/&/g,'&amp;')")
                .contains("replace(/</g,'&lt;')")
                .contains("replace(/>/g,'&gt;')");
        assertThat(body).as("the local node id is escaped, never interpolated raw").contains("esc(d.localNodeId)");
        assertThat(body).as("each node's id is escaped before it reaches the DOM").contains("esc(n.nodeId)");
        assertThat(body).as("a divergence's kind, node and free-text detail are all escaped")
                .contains("esc(x.kind)").contains("esc(x.nodeId)").contains("esc(x.detail)");
        // No untrusted field is dropped into innerHTML without the escaper: the only '+n.' / '+x.' / '+d.localNodeId'
        // interpolations of API data are the esc()-wrapped ones asserted above (the raw counts liveCount/nodeCount are
        // server-computed numbers, not attacker-controlled strings).
        assertThat(body).doesNotContain("+n.nodeId").doesNotContain("+x.detail").doesNotContain("+d.localNodeId+");
    }
}
