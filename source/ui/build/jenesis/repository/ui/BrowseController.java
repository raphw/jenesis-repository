package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The generic artifact browse: a breadcrumbed, lazy tree over any repository's published namespace, read through the
 * {@link ArtifactStore} listing seam (the framework-neutral "inventory" primitive - prefix listing, one level at a
 * time) so it is generic across every format. The tree is rooted at the {@code publish/} pointer tree the formats
 * write, so a browse walks the logical request paths ({@code maven/org/apache/…}), not the content-addressed
 * {@code blobs/} bucket. Each level lists only its immediate children ({@link ArtifactStore#list}); a folder's
 * children are fetched only when it is navigated into or expanded, so a browse never scans or buffers a whole tree,
 * and never reads an artifact blob - only the tiny publish pointer (its content is the blob hash) and the blob's
 * stored size feed the size column.
 *
 * <p>This lives in the free base so both consoles share one browse. It is deny-by-default authenticated (a GET
 * caught by {@code anyRequest().authenticated()}), and the {@code path} query parameter is traversal-guarded - any
 * {@code .}/{@code ..}/empty segment is dropped - so a request can never escape the {@code publish/} subtree to read
 * {@code blobs/} or a sibling's data. The reserved {@code publish/quarantine/} review subtree - artifacts the gate is
 * withholding, which a plain {@code GET} 404s and the {@code /assets} export never walks - is likewise excluded from
 * both the root listing and navigation, so the browse discloses exactly what a {@code GET} would.
 */
@Controller
public class BrowseController {

    /** The store subtree the browse is rooted at: the formats' published request-path pointer tree. */
    private static final String ROOT = "publish";

    /** The reserved top-level subtree under {@code publish/} that holds artifacts the gate is withholding: a GET does
     *  not serve them and the {@code /assets} export never walks them, so the interactive browse hides it too. */
    private static final String QUARANTINE = "quarantine";

    private final ArtifactStore store;
    private final Publication publication;

    public BrowseController(ArtifactStore store) {
        this.store = store;
        this.publication = new Publication(store);
    }

    /** The full browse page: the breadcrumb trail to {@code path} and the immediate children under it. */
    @GetMapping("/browse")
    public String browse(@RequestParam(name = "path", defaultValue = "") String path, Model model) throws IOException {
        String safe = sanitize(path);
        List<Map<String, Object>> entries = children(safe);
        model.addAttribute("path", safe);
        model.addAttribute("entries", entries);
        model.addAttribute("crumbs", crumbs(safe));
        model.addAttribute("hasParent", !safe.isEmpty());
        model.addAttribute("parent", parent(safe));
        return "browse";
    }

    /** The lazy-children fragment: just the child rows under {@code path}, fetched on demand when a folder expands. */
    @GetMapping("/browse/children")
    public String children(@RequestParam(name = "path", defaultValue = "") String path, Model model) throws IOException {
        model.addAttribute("entries", children(sanitize(path)));
        return "browse :: rows";
    }

    /**
     * The console face of the free {@code GET /api/assets} enumeration: a downloadable, streamed export of every
     * published asset in the repository as NDJSON (one {@code {"path","size","sha256"}} object per line), the outbound
     * mirror of the import connectors so getting your data out is never the paid feature. It walks the {@code publish/}
     * pointer tree through the same {@link ArtifactStore} listing seam the browse uses - reading only the tiny
     * publication pointer (its content <em>is</em> the blob hash) and the blob's stored size, never an artifact blob -
     * and writes each entry as it is reached, so an arbitrarily large repository exports without buffering the tree.
     * A path the store withholds (a retracted or quarantined artifact) is skipped through {@link Publication#located},
     * and the {@code /quarantine} review subtree is never walked, so the export serves exactly what a {@code GET}
     * would. It is deny-by-default authenticated like the browse (a GET any signed-in user may take); the coordinate
     * enrichment {@code /api/assets} adds needs the owning format, which this store-only console does not carry, so the
     * export carries the format-neutral pointer facts.
     */
    @GetMapping("/assets")
    public void assets(HttpServletResponse response) throws IOException {
        response.setHeader("Content-Type", "application/x-ndjson");
        response.setHeader("Content-Disposition", "attachment; filename=\"assets.ndjson\"");
        try (Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            walk("", out);
        }
    }

    /** Depth-first over the {@code publish/} pointer tree, emitting each leaf pointer as one NDJSON line; the
     *  content-addressed {@code blobs/} bucket and the {@code /quarantine} review subtree are never walked. */
    private void walk(String relative, Writer out) throws IOException {
        List<String> children = store.list(relative.isEmpty() ? ROOT : ROOT + "/" + relative);
        if (children.isEmpty()) {
            if (!relative.isEmpty()) {
                emit(relative, out);
            }
            return;
        }
        for (String child : children) {
            if (relative.isEmpty() && child.equals("quarantine")) {
                continue;
            }
            walk(relative.isEmpty() ? child : relative + "/" + child, out);
        }
    }

    /** Emit one published leaf as an NDJSON object; a pointer the store withholds (quarantined/retracted) or whose blob
     *  is gone resolves to no key and is skipped, so the export never names an unserved path. */
    private void emit(String relative, Writer out) throws IOException {
        String requestPath = "/" + relative;
        Optional<String> located = publication.located(requestPath);
        if (located.isEmpty()) {
            return;
        }
        String key = located.get();
        long size = store.size(key);
        String sha256 = key.substring("blobs/".length());
        out.write("{\"path\":\"" + jsonEscape(requestPath) + "\",\"size\":" + size
                + ",\"sha256\":\"" + jsonEscape(sha256) + "\"}\n");
    }

    /** Minimal JSON string escaping for the two fields the export carries - a request path and a hex digest - so a path
     *  segment carrying a quote, backslash or control character stays valid NDJSON. */
    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** The immediate children under a (sanitized) browse path, each classified folder-vs-artifact with a size. */
    private List<Map<String, Object>> children(String path) throws IOException {
        String prefix = path.isEmpty() ? ROOT : ROOT + "/" + path;
        int depth = path.isEmpty() ? 1 : path.split("/").length + 1;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : store.list(prefix)) {
            if (path.isEmpty() && name.equals(QUARANTINE)) {
                // The withheld-artifact review subtree is not part of the served namespace: it never appears in the
                // root folder listing (sanitize also refuses to navigate into it), so a browse discloses only the paths
                // and sizes a GET would - the same confinement the /assets export's walk applies.
                continue;
            }
            String childPath = path.isEmpty() ? name : path + "/" + name;
            boolean folder = !store.list(ROOT + "/" + childPath).isEmpty();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("path", childPath);
            entry.put("folder", folder);
            entry.put("depth", depth);
            entry.put("size", folder ? "—" : size("/" + childPath));
            entries.add(entry);
        }
        return entries;
    }

    /** The human-readable stored size of the blob a published request path points at, or {@code —} when unknown. */
    private String size(String requestPath) throws IOException {
        String key = publication.located(requestPath).orElse(null);
        if (key == null) {
            return "—";
        }
        long bytes = store.size(key);
        return bytes < 0 ? "—" : humanSize(bytes);
    }

    /** The breadcrumb trail: a clickable root plus one crumb per accumulated path segment (the last is the current). */
    private List<Map<String, String>> crumbs(String path) {
        List<Map<String, String>> crumbs = new ArrayList<>();
        crumbs.add(crumb("Repository", path.isEmpty() ? null : "/browse"));
        if (!path.isEmpty()) {
            String[] segments = path.split("/");
            StringBuilder accumulated = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                if (index > 0) {
                    accumulated.append('/');
                }
                accumulated.append(segments[index]);
                boolean last = index == segments.length - 1;
                crumbs.add(crumb(segments[index], last ? null : "/browse?path=" + accumulated));
            }
        }
        return crumbs;
    }

    private static Map<String, String> crumb(String label, String href) {
        Map<String, String> crumb = new LinkedHashMap<>();
        crumb.put("label", label);
        crumb.put("href", href);
        return crumb;
    }

    /** The parent browse path (empty for a one-segment path, so the up-link returns to the root). */
    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Drop every unsafe segment so the resulting path stays strictly under {@code publish/}: an empty, {@code .} or
     * {@code ..} segment, or one carrying a backslash, is removed rather than allowed to walk up out of the subtree.
     */
    private static String sanitize(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0) {
                continue;
            }
            if (safe.length() == 0 && segment.equals(QUARANTINE)) {
                // A leading "quarantine" segment would navigate into the withheld-artifact review subtree, whose paths
                // and sizes a GET does not serve; drop it (a deeper "quarantine" is a legitimate artifact-path segment
                // and is kept), so a crafted ?path=quarantine/... cannot enumerate held artifacts.
                continue;
            }
            if (safe.length() > 0) {
                safe.append('/');
            }
            safe.append(segment);
        }
        return safe.toString();
    }

    /** Bytes as a compact human-readable size (the browse size column), binary units, one decimal above a kilobyte. */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format("%.1f %s", value, units[unit]);
    }
}
