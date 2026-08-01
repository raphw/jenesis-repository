package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.LogPanel;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled recent-logs panel (WO.4): the console's window onto the server's {@code GET /api/logs} tail. Like its
 * sibling {@code ConsistencyPanel} it is a thin, key-gated live-API panel that reads nothing from the store, so it
 * renders with a null store; the read carries the {@code Jenesis-Repository-Key} header and needs {@code
 * repository:read}, and all dynamic, API-derived text is escaped in JS ({@code jlogsEsc}) before it reaches the DOM.
 */
class LogPanelTest {

    @Test
    void it_identifies_itself_as_the_logs_panel() {
        LogPanel panel = new LogPanel();
        assertThat(panel.id()).isEqualTo("logs");
        assertThat(panel.title()).isEqualTo("Logs");
    }

    @Test
    void it_renders_a_key_gated_live_read_of_the_log_tail() {
        // The panel reads no artifact data, so a null store is fine here (a live-API panel).
        String body = new LogPanel().render(null);

        assertThat(body).as("it reads the tail from the logs API").contains("/api/logs");
        assertThat(body).as("the read is key-gated like every other /api surface")
                .contains("Jenesis-Repository-Key").contains("repository:read");
    }

    @Test
    void every_api_derived_log_field_reaches_the_dom_through_an_html_escaper() {
        // Stronger than substring-checking the bare "jlogsEsc" identifier: prove the escaper actually neutralises the
        // three markup metacharacters and that every attacker-influenced field of a log row (a log message, logger
        // name, level, timestamp and tenant) is passed THROUGH the escaper before it is interpolated into innerHTML,
        // so a crafted log message cannot inject markup. (No script engine is on the module path in this JDK, so a
        // full JS-execution assertion is not possible.)
        String body = new LogPanel().render(null);

        assertThat(body).as("the escaper is published and maps & < > to their HTML entities")
                .contains("window.jlogsEsc=esc")
                .contains("replace(/&/g,'&amp;')")
                .contains("replace(/</g,'&lt;')")
                .contains("replace(/>/g,'&gt;')");
        assertThat(body).as("each column of a log row is escaped before it reaches the DOM")
                .contains("esc(e.timestamp)").contains("esc(e.level)").contains("esc(e.logger)")
                .contains("esc(e.message)").contains("esc(e.tenant)");
        // The free-text an attacker controls (the log message and logger) is built into the row only through the
        // escaper: it appears wrapped as esc(e.message) / esc(e.logger), never as a raw td interpolation of the value.
        assertThat(body).contains("<td>'+esc(e.logger)+'</td><td>'+esc(e.message)+'</td>");
    }
}
