package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * {@code blobs/} or a sibling's data.
 */
@Controller
public class BrowseController {

    /** The store subtree the browse is rooted at: the formats' published request-path pointer tree. */
    private static final String ROOT = "publish";

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

    /** The immediate children under a (sanitized) browse path, each classified folder-vs-artifact with a size. */
    private List<Map<String, Object>> children(String path) throws IOException {
        String prefix = path.isEmpty() ? ROOT : ROOT + "/" + path;
        int depth = path.isEmpty() ? 1 : path.split("/").length + 1;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : store.list(prefix)) {
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
