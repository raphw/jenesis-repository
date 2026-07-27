package build.jenesis.repository.ui;

import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.store.ArtifactStore;

import java.util.OptionalDouble;

/**
 * The metrics-overview panel (WO.2): the console's plain, no-graphs window onto <em>all</em> of this repository's
 * observability - every self-describing signal a plugin reports about itself, collected once through {@link
 * ObservabilityReport#discover()} and rendered as current values, health states and background-task status rather than
 * a time-series chart (the free counterpart of the enterprise console's metrics-overview page and its
 * {@code /api/admin/observability} admin API). Each display carries the <strong>description from its registration</strong>
 * - the same self-describing text Actuator and the reference docs show - so an operator who is not staring at Grafana
 * reads what a signal means, its current value, and, where a metric declares a {@code limit}, its used-vs-available and
 * how close to the ceiling it is (a plain number and bar, never a graph). The listing is <strong>searchable</strong>
 * (a client-side filter over each item's name and description) and degrades gracefully: a disabled or absent source
 * contributes nothing, so it is simply not listed, and a deployment with no source installed shows a friendly empty
 * state rather than an error. It reads no artifact data, so the {@link ArtifactStore} is unused; the console is an
 * authenticated operator surface and this panel only observes. Every signal-derived string is HTML-escaped before it is
 * placed in the fragment (the shell drops the body in unescaped).
 */
public final class ObservabilityPanel implements Panel {

    @Override
    public String id() {
        return "metrics";
    }

    @Override
    public String title() {
        return "Metrics overview";
    }

    @Override
    public String render(ArtifactStore store) {
        return renderReport(ObservabilityReport.discover());
    }

    /** Render a collected report as the panel body - the pure function the discovery path and the tests share, so the
     *  grouping, the descriptions, the used-vs-available and the graceful-empty state are proven without the ambient
     *  module graph. */
    public static String renderReport(ObservabilityReport report) {
        StringBuilder html = new StringBuilder();
        html.append("<p>Every signal this repository reports about itself, in one plain overview - current values, "
                + "health and background-task status, each with the description from its registration. No graphs; a "
                + "disabled or absent source is simply not listed. The read is collected once "
                + "(<code>ObservabilityReport.discover()</code>), the same source the enterprise console's "
                + "metrics-overview page and its <code>/api/admin/observability</code> admin API render.</p>");
        html.append("<p>Overall health: <strong>").append(escape(report.overall().name())).append("</strong></p>");

        boolean empty = report.healthChecks().isEmpty() && report.metrics().isEmpty() && report.tasks().isEmpty();
        if (empty) {
            html.append("<p>No observability sources are installed, so there is nothing to report yet. "
                    + "Install a plugin that reports health, metrics or task status and it appears here.</p>");
            return html.toString();
        }

        html.append("<label>Search <input id=\"jmetrics-q\" type=\"search\" "
                + "aria-label=\"Filter signals by name or description\" "
                + "placeholder=\"text in a signal name or description\"></label>");

        html.append("<h4>Health</h4>");
        if (report.healthChecks().isEmpty()) {
            html.append("<p class=\"jmetrics-none\">No health checks reported.</p>");
        } else {
            for (HealthCheck check : report.healthChecks()) {
                String search = check.name() + " " + check.description() + " " + check.status().name();
                html.append("<article class=\"jmetrics-item\" data-search=\"").append(escape(search)).append("\">");
                html.append("<strong>").append(escape(check.name())).append("</strong> — ")
                        .append("<span>").append(escape(check.status().name())).append("</span>");
                html.append("<br><small>").append(escape(check.description())).append("</small>");
                if (!check.detail().isEmpty()) {
                    html.append("<br><small><code>").append(escape(check.detail())).append("</code></small>");
                }
                html.append("</article>");
            }
        }

        html.append("<h4>Metrics</h4>");
        if (report.metrics().isEmpty()) {
            html.append("<p class=\"jmetrics-none\">No metrics reported.</p>");
        } else {
            for (Metric metric : report.metrics()) {
                String search = metric.name() + " " + metric.description() + " " + metric.unit();
                html.append("<article class=\"jmetrics-item\" data-search=\"").append(escape(search)).append("\">");
                html.append("<strong>").append(escape(metric.name())).append("</strong> — ")
                        .append("<span>").append(escape(number(metric.value())));
                if (!metric.unit().isEmpty()) {
                    html.append(' ').append(escape(metric.unit()));
                }
                html.append("</span> <small>(").append(escape(metric.kind().name().toLowerCase())).append(")</small>");
                OptionalDouble limit = metric.limit();
                OptionalDouble usage = metric.usage();
                if (limit.isPresent() && usage.isPresent()) {
                    long percent = Math.round(usage.getAsDouble() * 100);
                    html.append("<br><small>").append(escape(number(metric.value()))).append(" of ")
                            .append(escape(number(limit.getAsDouble()))).append(" used — ").append(percent)
                            .append("%</small>");
                    // A plain progress bar, not a time-series graph: the fraction of the limit occupied right now.
                    html.append("<div><progress value=\"").append(clampPercent(percent)).append("\" max=\"100\">")
                            .append(percent).append("%</progress></div>");
                }
                html.append("<br><small>").append(escape(metric.description())).append("</small>");
                html.append("</article>");
            }
        }

        html.append("<h4>Background tasks</h4>");
        if (report.tasks().isEmpty()) {
            html.append("<p class=\"jmetrics-none\">No background tasks reported.</p>");
        } else {
            for (TaskStatus task : report.tasks()) {
                String search = task.name() + " " + task.description() + " " + task.state().name();
                html.append("<article class=\"jmetrics-item\" data-search=\"").append(escape(search)).append("\">");
                html.append("<strong>").append(escape(task.name())).append("</strong> — ")
                        .append("<span>").append(escape(task.state().name())).append("</span>");
                if (task.everRan()) {
                    html.append(" <small>last run ").append(escape(String.valueOf(task.lastRun())));
                    if (task.lastDuration() != null) {
                        html.append(" (").append(escape(String.valueOf(task.lastDuration()))).append(')');
                    }
                    if (!task.outcome().isEmpty()) {
                        html.append(" — ").append(escape(task.outcome()));
                    }
                    html.append("</small>");
                }
                html.append("<br><small>").append(escape(task.description())).append("</small>");
                html.append("</article>");
            }
        }

        // The searchable filter: a purely in-memory pass over the rendered items, matching each item's data-search
        // (its name + description + state) so a signal is found by what it does, not just by its name.
        html.append("""
                <script>
                (function(){
                  var input=document.getElementById('jmetrics-q');
                  if(!input)return;
                  input.addEventListener('input',function(){
                    var q=input.value.trim().toLowerCase();
                    document.querySelectorAll('.jmetrics-item').forEach(function(el){
                      var hay=(el.getAttribute('data-search')||'').toLowerCase();
                      el.style.display=(!q||hay.indexOf(q)>=0)?'':'none';
                    });
                  });
                })();
                </script>
                """);
        return html.toString();
    }

    /** A whole-valued signal renders without a trailing {@code .0}; a fractional one keeps its point. */
    private static String number(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? Long.toString((long) value)
                : Double.toString(value);
    }

    /** A usage percent can exceed 100 (a signal over its ceiling); the plain bar saturates at full rather than
     *  overflowing. */
    private static long clampPercent(long percent) {
        return Math.max(0, Math.min(100, percent));
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
