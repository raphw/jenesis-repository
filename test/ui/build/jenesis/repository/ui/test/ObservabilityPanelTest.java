package build.jenesis.repository.ui.test;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.ui.ObservabilityPanel;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metrics-overview panel (WO.2) renders the whole collected {@link ObservabilityReport} as a plain, no-graphs
 * overview - current values, health and background-task status, each carrying the description from its registration -
 * grouped by signal kind and searchable. A hand-built report is the fixture (the pure {@code renderReport} the
 * discovery path shares), so the grouping, the self-describing descriptions, the used-vs-available of a bounded metric
 * and the graceful-empty state are proven without an ambient {@code ObservabilitySource} on the module path.
 */
class ObservabilityPanelTest {

    @Test
    void it_identifies_itself_as_the_metrics_overview_panel() {
        ObservabilityPanel panel = new ObservabilityPanel();
        assertThat(panel.id()).isEqualTo("metrics");
        assertThat(panel.title()).isEqualTo("Metrics overview");
    }

    @Test
    void it_renders_each_signal_with_its_registration_description_grouped_by_kind() {
        ObservabilityReport report = new ObservabilityReport(
                List.of(HealthCheck.of("jenesis.store.reachable", "Whether the artifact store answers",
                        Health.DEGRADED, "slow backend")),
                List.of(Metric.counter("jenesis.gateway.requests", "Requests served since boot", 42, "requests")),
                List.of(TaskStatus.ran("jenesis.gc.sweep", "The blob reclamation sweep", TaskStatus.State.IDLE,
                        Instant.parse("2026-07-27T00:00:00Z"), Duration.ofSeconds(3), "reclaimed 5 blobs")));

        String body = ObservabilityPanel.renderReport(report);

        assertThat(body).as("the panel renders its plain overview intro").contains("plain overview");
        assertThat(body).as("the three signal kinds are grouped under headings")
                .contains("<h4>Health</h4>").contains("<h4>Metrics</h4>").contains("<h4>Background tasks</h4>");
        assertThat(body).as("the health check shows its name, state and registration description")
                .contains("jenesis.store.reachable").contains("DEGRADED")
                .contains("Whether the artifact store answers").contains("slow backend");
        assertThat(body).as("the metric shows its current value, unit and registration description")
                .contains("jenesis.gateway.requests").contains("42").contains("requests")
                .contains("Requests served since boot");
        assertThat(body).as("the task shows its state, last-run and registration description")
                .contains("jenesis.gc.sweep").contains("IDLE").contains("The blob reclamation sweep")
                .contains("reclaimed 5 blobs");
    }

    @Test
    void a_bounded_metric_shows_used_versus_available_as_a_plain_bar_no_graph() {
        ObservabilityReport report = new ObservabilityReport(List.of(),
                List.of(Metric.bounded("jenesis.quota.bytes", "Storage used against the tenant quota",
                        750, 1000, "bytes")),
                List.of());

        String body = ObservabilityPanel.renderReport(report);

        assertThat(body).as("the used-vs-available is shown as plain numbers and a percentage")
                .contains("750 of 1000 used").contains("75%");
        assertThat(body).as("the usage fraction renders as a plain progress bar, never a time-series graph")
                .contains("<progress value=\"75\" max=\"100\"");
    }

    @Test
    void an_empty_report_degrades_to_a_friendly_no_sources_state_never_an_error() {
        String body = ObservabilityPanel.renderReport(
                new ObservabilityReport(List.of(), List.of(), List.of()));
        assertThat(body).as("a deployment with no source installed reads a friendly empty state")
                .contains("No observability sources are installed");
        assertThat(body).as("with nothing to filter, no search box is rendered").doesNotContain("jmetrics-q");
    }

    @Test
    void the_listing_is_searchable_by_name_and_description() {
        ObservabilityReport report = new ObservabilityReport(
                List.of(HealthCheck.up("jenesis.store.reachable", "Whether the artifact store answers")),
                List.of(), List.of());

        String body = ObservabilityPanel.renderReport(report);

        assertThat(body).as("a client-side filter box is rendered").contains("id=\"jmetrics-q\"")
                .contains("type=\"search\"");
        assertThat(body).as("each item carries a data-search string of its name and description so the filter "
                        + "matches by what a signal does, not just its name")
                .contains("data-search=\"jenesis.store.reachable Whether the artifact store answers");
    }
}
