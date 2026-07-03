package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;
import java.util.List;

/**
 * The bundled browse panel: the console's entry point into the generic artifact browse ({@link BrowseController} at
 * {@code /browse}), replacing the former flat placeholder dump. It links into the breadcrumbed lazy tree and previews
 * the repository's top-level published namespaces (the formats' request-path roots) as quick links, so the console is
 * usable out of the box and a plugged-in panel has a worked example to copy. It reads only the first level of the
 * {@code publish/} pointer tree through the {@link ArtifactStore} - never an artifact blob. Repository-derived names
 * are HTML-escaped before they are placed in the fragment (the shell drops the body in unescaped).
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
        StringBuilder html = new StringBuilder();
        html.append("<p>Browse the repository's published artifacts as a breadcrumbed, navigable tree.</p>");
        html.append("<p><a href=\"/browse\" role=\"button\" class=\"secondary\">Open the repository browser &rarr;</a></p>");
        List<String> published = store.list("publish");
        if (published.isEmpty()) {
            html.append("<p>The repository is empty. Publish an artifact to see it here.</p>");
            return html.toString();
        }
        html.append("<p>Published namespaces:</p><ul>");
        for (String name : published) {
            html.append("<li><a href=\"/browse?path=").append(urlEscape(name)).append("\">")
                    .append(escape(name)).append("</a></li>");
        }
        html.append("</ul>");
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

    /** Percent-encode a namespace name for the {@code path} query parameter, so a name is safe in the href. */
    private static String urlEscape(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.forDigit((c >> 4) & 0xF, 16))
                        .append(Character.forDigit(c & 0xF, 16));
            }
        }
        return encoded.toString();
    }
}
