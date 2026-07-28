package build.jenesis.repository.ui;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The Security-posture panel (WO.5): the console's window onto <em>all</em> of this deployment's configuration-warning
 * advisories - every potentially-unsafe setting a discovered {@link build.jenesis.repository.posture.SafetyAdvisor}
 * raises against the effective configuration, collected once through {@link PostureReport#discover} and rendered
 * severity-sorted (critical first). Each row shows <strong>why</strong> a setting is unsafe, the suggested safer
 * alternative and the <strong>exact {@code jenesis.*} key/value</strong> that fixes it, plus a docs link - the free
 * counterpart of the enterprise console's Security-posture page and its {@code /api/admin/posture} admin API.
 *
 * <p>It reads no artifact data, so the {@link ArtifactStore} is unused; the console is an authenticated operator surface
 * and this panel only observes (read-only - observing posture never mutates it). The advisory text names the risk
 * without repeating any read secret value, so this surface cannot itself leak one. It degrades gracefully: a clean
 * deployment shows a friendly "no advisories" state rather than an error. Deployment-wide advisories are shown here (the
 * free console's operator is the deployment admin); a downstream multi-tenant distribution scopes tenant advisories to
 * that tenant's admins through {@link PostureReport#forTenant}. Every advisory-derived string is HTML-escaped before it
 * is placed in the fragment (the shell drops the body in unescaped).
 */
public final class PosturePanel implements Panel {

    private final UnaryOperator<String> config;

    /** Wired by {@link UiConfig} with the deployment configuration lookup (the Spring {@code Environment}), so the
     *  panel body and the header badge ({@link ConsoleAdvice}) read the <em>same</em> effective configuration. */
    public PosturePanel(UnaryOperator<String> config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String id() {
        return "posture";
    }

    @Override
    public String title() {
        return "Security posture";
    }

    @Override
    public String render(ArtifactStore store) {
        return renderReport(PostureReport.discover(Configuration.of(config)));
    }

    /** Render a collected report as the panel body - the pure function the discovery path and the tests share, so the
     *  severity ordering, the why + fix + key/value, and the graceful-empty state are proven without the ambient module
     *  graph or a live {@code Environment}. */
    public static String renderReport(PostureReport report) {
        StringBuilder html = new StringBuilder();
        html.append("<p>Every potentially-unsafe configuration this deployment reports about itself, in one plain "
                + "overview - each with why it is unsafe, a suggested safer alternative and the exact "
                + "<code>jenesis.*</code> setting that fixes it. The read is collected once "
                + "(<code>PostureReport.discover()</code>), the same source the enterprise console's Security-posture "
                + "page and its <code>/api/admin/posture</code> admin API render. Read-only: observing posture never "
                + "changes it, and no advisory prints a secret value.</p>");

        var advisories = report.scoped(Scope.DEPLOYMENT);
        if (advisories.isEmpty()) {
            html.append("<p>No security-posture advisories - the effective configuration raises none. New advisories "
                    + "appear here as soon as a setting or a plugged-in module reports one.</p>");
            return html.toString();
        }

        html.append("<p>Overall: <strong>").append(report.count(build.jenesis.repository.posture.Severity.CRITICAL))
                .append(" critical</strong>, ").append(report.count(build.jenesis.repository.posture.Severity.WARN))
                .append(" warn, ").append(report.count(build.jenesis.repository.posture.Severity.INFO))
                .append(" info.</p>");

        for (SecurityAdvisory advisory : advisories) {
            html.append("<article class=\"app-card\">");
            html.append("<header><strong>").append(escape(badge(advisory.severity()))).append(' ')
                    .append(escape(advisory.title())).append("</strong> ")
                    .append("<small><code>").append(escape(advisory.id())).append("</code></small></header>");
            html.append("<p><strong>Why:</strong> ").append(escape(advisory.why())).append("</p>");
            html.append("<p><strong>Fix:</strong> ").append(escape(advisory.fix()));
            if (!advisory.settingKey().isEmpty()) {
                html.append(" <code>").append(escape(advisory.settingKey())).append('=')
                        .append(escape(advisory.settingValue())).append("</code>");
            }
            html.append("</p>");
            if (!advisory.docs().isEmpty()) {
                html.append("<p><a href=\"").append(escape(advisory.docs()))
                        .append("\" rel=\"noopener noreferrer\" target=\"_blank\">Documentation</a></p>");
            }
            html.append("</article>");
        }
        return html.toString();
    }

    /** A short severity marker for the row header (no colour dependency - a plain text tag). */
    private static String badge(build.jenesis.repository.posture.Severity severity) {
        return switch (severity) {
            case CRITICAL -> "[CRITICAL]";
            case WARN -> "[WARN]";
            case INFO -> "[INFO]";
        };
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
