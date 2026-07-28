package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.ConsistencyPanel;
import org.junit.jupiter.api.Test;

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
        assertThat(body).as("dynamic text is escaped before it reaches the DOM").contains("jconEsc");
    }
}
