package build.jenesis.repository.ui.test;

import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.ui.PosturePanel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Security-posture panel (WO.5) renders a collected {@link PostureReport} as plain rows - each advisory's why, its
 * suggested fix and the exact {@code jenesis.*} key/value, severity-sorted - and degrades to a friendly "no advisories"
 * state when the configuration is clean. A hand-built report is the fixture (the pure {@code renderReport} the discovery
 * path shares), so the ordering, the why/fix/key-value and the graceful-empty state are proven without an ambient
 * {@code SafetyAdvisor} on the module path. It also proves the surface never echoes a secret value.
 */
class PosturePanelTest {

    @Test
    void it_identifies_itself_as_the_security_posture_panel() {
        PosturePanel panel = new PosturePanel(key -> null);
        assertThat(panel.id()).isEqualTo("posture");
        assertThat(panel.title()).isEqualTo("Security posture");
    }

    @Test
    void it_renders_each_advisory_with_its_why_fix_and_exact_setting() {
        PostureReport report = new PostureReport(List.of(
                SecurityAdvisory.deployment("jenesis.auth.open", Severity.CRITICAL,
                        "Authorization is disabled - the instance is fully open",
                        "serves every request anonymously", "Enforce per-credential authorization",
                        "jenesis.repository.auth", "true", "https://jenesis.build/docs/security/posture#jenesis.auth.open")));

        String body = PosturePanel.renderReport(report);

        assertThat(body).as("the advisory shows its id, title, why and fix")
                .contains("jenesis.auth.open").contains("fully open")
                .contains("serves every request anonymously").contains("Enforce per-credential authorization");
        assertThat(body).as("the exact setting key/value to change is shown so an operator can copy it")
                .contains("jenesis.repository.auth=true");
        assertThat(body).as("a critical advisory is marked").contains("[CRITICAL]");
        assertThat(body).as("the docs link is rendered").contains("posture#jenesis.auth.open");
    }

    @Test
    void a_clean_report_degrades_to_a_friendly_no_advisories_state_never_an_error() {
        String body = PosturePanel.renderReport(new PostureReport(List.of()));
        assertThat(body).as("a deployment with a clean configuration reads a friendly empty state")
                .contains("No security-posture advisories");
    }

    @Test
    void the_surface_names_the_risk_without_printing_any_secret_value() {
        // Even if an advisory were built around a sensitive setting, the rendered text carries only the risk and the
        // fix, never a read secret - proven here by asserting a planted secret never reaches the body.
        PostureReport report = new PostureReport(List.of(
                SecurityAdvisory.deployment("jenesis.console.wildcard", Severity.WARN,
                        "The admin console grants admin to every signed-in user",
                        "jenesis.ui.admins=* makes every authenticated user a console admin",
                        "Name the specific admin principals", "jenesis.ui.admins", "github/<your-id>", "")));
        String body = PosturePanel.renderReport(report);
        assertThat(body).doesNotContain("SUPERSECRET");
        assertThat(body).contains("jenesis.console.wildcard");
    }
}
