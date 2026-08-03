package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishedAssets;
import build.jenesis.repository.store.ServableNames;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import module java.base;

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

    private final ArtifactStore store;
    private final Publication publication;
    private final PublishedAssets assets;
    private final ServableNames names;

    public BrowseController(ArtifactStore store) {
        this.store = store;
        this.publication = new Publication(store);
        this.assets = new PublishedAssets(store, publication);
        this.names = new ServableNames(store, publication);
    }

    /** The full browse page: the breadcrumb trail to {@code path} and the immediate children under it. */
    @GetMapping("/browse")
    public String browse(@RequestParam(name = "path", defaultValue = "") String path, Model model) throws IOException {
        String safe = sanitize(path);
        Listing listing = children(safe);
        model.addAttribute("path", safe);
        model.addAttribute("entries", listing.entries());
        model.addAttribute("truncated", listing.truncated());
        model.addAttribute("cap", MAX_CHILDREN);
        model.addAttribute("crumbs", crumbs(safe));
        model.addAttribute("hasParent", !safe.isEmpty());
        model.addAttribute("parent", parent(safe));
        return "browse";
    }

    /** The lazy-children fragment: just the child rows under {@code path}, fetched on demand when a folder expands. */
    @GetMapping("/browse/children")
    public String children(@RequestParam(name = "path", defaultValue = "") String path, Model model) throws IOException {
        Listing listing = children(sanitize(path));
        model.addAttribute("entries", listing.entries());
        model.addAttribute("truncated", listing.truncated());
        model.addAttribute("cap", MAX_CHILDREN);
        return "browse :: rows";
    }

    /**
     * The console face of the free {@code GET /api/assets} enumeration: a downloadable, streamed export of every
     * published asset in the repository as NDJSON (one {@code {"path","size","sha256"}} object per line), the outbound
     * mirror of the import connectors so getting your data out is never the paid feature. It walks the {@code publish/}
     * pointer tree through the shared {@link PublishedAssets} walk the server's {@code /api/assets} catalogue also uses
     * - reading only the tiny publication pointer (its content <em>is</em> the blob hash) and the blob's stored size,
     * never an artifact blob - and writes each entry as it is reached, so an arbitrarily large repository exports
     * without buffering the tree. A path the store withholds (a retracted or quarantined artifact) is skipped and the
     * {@code /quarantine} review subtree is never walked (both are the shared walk's own guarantees), so the export
     * serves exactly what a {@code GET} would. It is deny-by-default authenticated like the browse (a GET any signed-in
     * user may take); the coordinate enrichment {@code /api/assets} adds needs the owning format, which this store-only
     * console does not carry, so the export carries the format-neutral pointer facts the walk emits, as NDJSON.
     */
    @GetMapping("/assets")
    public void assets(HttpServletResponse response) throws IOException {
        response.setHeader("Content-Type", "application/x-ndjson");
        response.setHeader("Content-Disposition", "attachment; filename=\"assets.ndjson\"");
        try (Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            assets.walk(null, Integer.MAX_VALUE, entry -> emit(entry, out));
        }
    }

    /** Render one walked pointer as an NDJSON object; the domain walk already skipped withheld pointers and the
     *  quarantine subtree, so this is pure presentation - the one thing that legitimately lives in the controller. */
    private static void emit(PublishedAssets.Entry entry, Writer out) throws IOException {
        out.write("{\"path\":\"" + jsonEscape(entry.path()) + "\",\"size\":" + entry.size()
                + ",\"sha256\":\"" + jsonEscape(entry.sha256()) + "\"}\n");
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

    /** A page of immediate children under a browse path plus whether the directory held more than the render cap. */
    private record Listing(List<Map<String, Object>> entries, boolean truncated) {
    }

    /** The most immediate children a single browse renders. A directory with an enormous fan-out (a repo with a
     *  million top-level packages, or a coordinate with hundreds of thousands of timestamped versions) is navigated
     *  into, not scrolled, so the console caps what it materialises rather than building - and rendering - the whole
     *  set in heap. */
    private static final int MAX_CHILDREN = 1000;

    /** The immediate children under a (sanitized) browse path, each classified folder-vs-artifact with a size - paged
     *  and capped so a high-fan-out directory can never materialise a millions-entry {@code List} (or fire a store
     *  round-trip per child) and OOM the console. */
    private Listing children(String path) throws IOException {
        String prefix = path.isEmpty() ? ROOT : ROOT + "/" + path;
        int depth = path.isEmpty() ? 1 : path.split("/").length + 1;
        List<Map<String, Object>> entries = new ArrayList<>();
        int[] seen = {0};
        // Page the immediate children (one past the cap, to detect truncation) instead of materialising the whole
        // directory as one List; a real backend seeks rather than re-lists. The withheld-review subtree is never part
        // of the served namespace, so it is skipped at the root (through the servable-name seam's one home of the
        // reserved name). A leaf is disclosed only when the seam judges it servable under HIDE_WITHHELD_AND_GONE
        // (published, blob present, not withheld) - the same serve-screen the raw listing and the /assets export apply,
        // routed through the one seam so the browse can never disagree with a GET on what is held: a retracted or
        // quarantined artifact, or a pointer whose blob a garbage collection reclaimed, is never leaked by name or tree
        // position. A sub-directory is kept unconditionally (it is a listing, not a servable leaf). `seen` counts the
        // raw children so a screened-out leaf can never hide the truncation flag (it stays keyed on the store's child
        // count, not the rendered rows).
        try {
            store.page(prefix, "", MAX_CHILDREN + 1, name -> {
                if (path.isEmpty() && ServableNames.reviewSubtree(name)) {
                    return;
                }
                seen[0]++;
                if (entries.size() >= MAX_CHILDREN) {
                    return;                              // render cap reached; keep counting `seen` for truncation
                }
                String childPath = path.isEmpty() ? name : path + "/" + name;
                try {
                    boolean folder = hasChild(ROOT + "/" + childPath);
                    String size;
                    if (folder) {
                        size = "—";
                    } else {
                        if (!names.disclosable("/" + childPath, ServableNames.Policy.HIDE_WITHHELD_AND_GONE)) {
                            return;                      // a leaf a GET would not serve: do not disclose its name
                        }
                        Optional<String> located = publication.located("/" + childPath);
                        if (located.isEmpty()) {
                            return;                      // raced away between the screen and the size read
                        }
                        long bytes = store.size(located.get());
                        size = bytes < 0 ? "—" : humanSize(bytes);
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", name);
                    entry.put("path", childPath);
                    entry.put("folder", folder);
                    entry.put("depth", depth);
                    entry.put("size", size);
                    entries.add(entry);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        boolean truncated = seen[0] > MAX_CHILDREN;
        return new Listing(entries, truncated);
    }

    /** Whether a prefix has at least one immediate child, tested with a bounded one-element page rather than listing
     *  (and discarding) the child's entire subtree just to check emptiness - so classifying a child as a folder is a
     *  single seek, not O(its own child count) round-trips (the old {@code list(...).isEmpty()} was a full subtree
     *  scan per child, quadratic across a large directory). */
    private boolean hasChild(String prefix) {
        boolean[] any = {false};
        store.page(prefix, "", 1, name -> any[0] = true);
        return any[0];
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
            if (safe.length() == 0 && ServableNames.reviewSubtree(segment)) {
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
