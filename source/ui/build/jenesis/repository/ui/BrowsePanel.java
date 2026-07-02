package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;
import java.util.List;

/**
 * The bundled browse panel: it lists the repository's real contents through the {@link ArtifactStore} rather than the
 * former placeholder text, so the console is usable out of the box and serves as the worked example a plugged-in panel
 * copies. It shows the top-level entries of the store and, when present, the first level of published request paths
 * (the {@code publish/} pointer tree the formats write). Repository-derived names are HTML-escaped before they are
 * placed in the fragment.
 */
public final class BrowsePanel implements Panel {

    @Override
    public String id() {
        return "browse";
    }

    @Override
    public String title() {
        return "Browse";
    }

    @Override
    public String render(ArtifactStore store) throws IOException {
        List<String> roots = store.list("");
        if (roots.isEmpty()) {
            return "<p>The repository is empty. Publish an artifact to see it here.</p>";
        }
        StringBuilder html = new StringBuilder();
        html.append("<p>Top-level entries in the artifact store:</p><ul>");
        for (String name : roots) {
            html.append("<li>").append(escape(name)).append("</li>");
        }
        html.append("</ul>");
        List<String> published = store.list("publish");
        if (!published.isEmpty()) {
            html.append("<p>Published paths:</p><ul>");
            for (String name : published) {
                html.append("<li>").append(escape(name)).append("</li>");
            }
            html.append("</ul>");
        }
        return html.toString();
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
