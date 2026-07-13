package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

import java.lang.module.ModuleDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The SPI catalogue panel: the console's read-only view of this repository's plug-in surface, grouped by SPI. Every
 * capability is a discovered {@code ServiceLoader} contract with one or more installed implementations - a format, a
 * storage backend, an import source, a login mechanism - and this panel lists each contract and the implementations
 * that {@code provide} it, so an operator sees the whole extension surface at a glance (the free counterpart of the
 * enterprise console's SPI catalogue and its {@code /api/admin/spi} admin API). Pure discovery over the JPMS module
 * graph's {@code provides} declarations, filtered to the product's own {@code build.jenesis.} namespace so the
 * framework's own {@code ServiceLoader} plumbing is not noise; it reads no artifact data, so the {@link ArtifactStore}
 * is unused. The console is an authenticated operator surface; this panel only observes, it never mutates. Service and
 * provider names are HTML-escaped before they are placed in the fragment (the shell drops the body in unescaped).
 */
public final class SpiCatalogPanel implements Panel {

    @Override
    public String id() {
        return "spi";
    }

    @Override
    public String title() {
        return "SPI catalog";
    }

    @Override
    public String render(ArtifactStore store) {
        Map<String, List<String[]>> byService = new TreeMap<>();
        for (Module module : ModuleLayer.boot().modules()) {
            ModuleDescriptor descriptor = module.getDescriptor();
            if (descriptor == null) {
                continue;
            }
            for (ModuleDescriptor.Provides provides : descriptor.provides()) {
                if (!provides.service().startsWith("build.jenesis.")) {
                    continue;
                }
                for (String provider : provides.providers()) {
                    byService.computeIfAbsent(provides.service(), _ -> new ArrayList<>())
                            .add(new String[]{provider, module.getName()});
                }
            }
        }
        StringBuilder html = new StringBuilder();
        html.append("<p>Every capability this repository carries is a discovered <strong>SPI</strong> (a plug-in "
                + "contract) with one or more installed <strong>implementations</strong>. This is a read-only view "
                + "of the whole plug-in surface, grouped by SPI; whether an implementation is active is configured "
                + "with its <code>jenesis.repository.&lt;feature&gt;</code> key.</p>");
        if (byService.isEmpty()) {
            html.append("<p>No SPIs are installed.</p>");
            return html.toString();
        }
        for (Map.Entry<String, List<String[]>> entry : byService.entrySet()) {
            List<String[]> implementations = entry.getValue();
            implementations.sort(Comparator.comparing(implementation -> implementation[0]));
            html.append("<article><h4>").append(escape(simpleName(entry.getKey()))).append("</h4>");
            html.append("<p><small><code>").append(escape(entry.getKey())).append("</code></small></p><ul>");
            for (String[] implementation : implementations) {
                html.append("<li><strong>").append(escape(simpleName(implementation[0])))
                        .append("</strong> &mdash; <code>").append(escape(implementation[1])).append("</code></li>");
            }
            html.append("</ul></article>");
        }
        return html.toString();
    }

    /** The short name of a fully-qualified type (its last dot-separated segment), for a compact heading. */
    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
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
