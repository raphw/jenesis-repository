package build.jenesis.repository.server;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.QuotaExceededException;
import build.jenesis.repository.store.ReadOnlyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The HTTP surface of the free repository, mirroring {@link RepositoryApplication}'s framework-neutral
 * dispatch but over Spring MVC. A catch-all resolves the request to its artifact space through {@link RepositoryRouting}
 * (fixed-tenant by default) and offers it the {@link RepositoryFormat} plugins over that doubly-scoped store through the
 * shared {@link FormatDispatcher}: the first format whose {@code handles(path)} is true serves or accepts the request
 * through a {@link ServletFormatExchange}; an unclaimed path is a {@code 404}. When an upstream is configured for the
 * matched format and the format is a {@link ProxyFormat}, a local miss is served through the {@link PullThroughCache}
 * from that upstream and cached, so a later read is a local hit. {@code /repository/admin/import} triggers an
 * asynchronous migration through the first {@link ImportSourceProvider} that handles the requested source - discovered
 * with {@code ServiceLoader} like the formats, so the server knows no incumbent by name - run as a background
 * {@link ImportJobs} writing into the request's routed artifact space (so an import lands exactly where serving reads),
 * and {@code GET /repository/admin/import/<id>} returns its state. Authorization is not done here:
 * {@link RepositorySecurityAutoConfiguration} gates the wire through the {@link Authorization} credential model.
 */
@RestController
public class RepositoryController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A routable repository name, the same traversal-free segment shape the multi-tenant edition validates, so a
     *  {@code repo=} query parameter can never escape its store scope (no {@code /}, {@code \} or {@code ..}). */
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_-]+");

    private static final int DEFAULT_PAGE = 500;
    private static final int MAX_PAGE = 1000;

    private final RepositoryRouting routing;
    private final FormatDispatcher dispatcher;
    private final List<ImportSourceProvider> importSources;
    private final ProxyFormat.Fetcher fetcher;
    private final BatchIngestion batch;
    private final UnaryOperator<String> settings;
    private final ArtifactStore root;
    private final RoutedServing routed;

    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher) {
        this(routing, dispatcher, importSources, fetcher, null);
    }

    /** As above, with batch archive ingestion wired in: a {@code PUT}/{@code POST} carrying the explode header is
     *  exploded into per-entry publishes through the same {@link FormatDispatcher} when {@code batch} claims it.
     *  A {@code null} {@code batch} leaves the feature off (the header is then an inert plain upload). */
    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher,
                                BatchIngestion batch) {
        this(routing, dispatcher, importSources, fetcher, batch, key -> null);
    }

    /** As above, resolving each request's {@link build.jenesis.repository.format.FormatExchange#setting(String)}
     *  through {@code settings} (a bare setting key to its effective value, {@code null} when unset), so a format can
     *  read a deployment toggle - the Maven metadata computation opt-in, say - off the exchange. A deployment builds
     *  it from its configuration; {@code key -> null} keeps every format on its shipped default. */
    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher,
                                BatchIngestion batch,
                                UnaryOperator<String> settings) {
        this(routing, dispatcher, importSources, fetcher, batch, settings, null);
    }

    /** As above, holding the un-scoped {@code root} {@link ArtifactStore} so the {@code /api/assets} enumeration can
     *  scope to an explicitly named {@code repo} within the request's tenant ({@code root.scope(tenant).scope(repo)},
     *  the same chain {@link RepositoryRouting} resolves). A {@code null} {@code root} leaves the enumeration on the
     *  request's own routed space, so the convenience constructors above still serve the fixed-tenant deployment. */
    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher,
                                BatchIngestion batch,
                                UnaryOperator<String> settings,
                                ArtifactStore root) {
        this(routing, dispatcher, importSources, fetcher, batch, settings, root, RoutedServing.NONE);
    }

    /** As above, consulting {@code routed} on a read ({@code GET}/{@code HEAD}) so a repository defined as a
     *  read-through proxy or a group view serves across its backings rather than only its own hosted store; a plain
     *  hosted repository is dispatched normally, keeping the format-level pull-through. {@link RoutedServing#NONE}
     *  (the default the convenience constructors pass) leaves every repository on its own store, so the free
     *  single-tenant edition is unchanged. */
    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher,
                                BatchIngestion batch,
                                UnaryOperator<String> settings,
                                ArtifactStore root,
                                RoutedServing routed) {
        this.routing = routing;
        this.dispatcher = dispatcher;
        this.importSources = importSources;
        this.fetcher = fetcher;
        this.batch = batch;
        this.settings = settings;
        this.root = root;
        this.routed = routed;
    }

    /**
     * The format catch-all: an artifact request under {@code /repository/**} (its prefix stripped by
     * {@link RepositoryRouting} before dispatch) or the OCI {@code /v2/**} registry the Docker protocol pins at the host
     * root, resolved to its artifact space and offered to the {@link RepositoryFormat} plugins over that store by the
     * {@link FormatDispatcher}. More specific routes ({@code /repository/admin/import}) and the Actuator endpoints win in
     * Spring, so this only sees a format's own paths; an unclaimed one is a {@code 404}. A format with a configured
     * upstream that is a {@link ProxyFormat} serves a local miss through the {@link PullThroughCache}. A write
     * carrying the batch explode header is walked entry by entry by {@link BatchIngestion} - each member dispatched
     * as its own publish through the same loop - when the feature is enabled; otherwise the header is inert and the
     * body is a plain single upload.
     */
    @RequestMapping(value = {"/repository/**", "/v2", "/v2/**"}, method = {RequestMethod.GET, RequestMethod.HEAD,
            RequestMethod.PUT, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RepositoryRouting.Route route = routing.route(request);
        ServletFormatExchange exchange = new ServletFormatExchange(request, response, route.path(), settings);
        if (batch != null && batch.claims(exchange)) {
            batch.explode(exchange, route.store(), dispatcher);
            return;
        }
        // A read of a routed repository (a proxy of an upstream, or a group view over members) is served across its
        // backings through the routing seam - a proxy pulls through its own upstream on a local miss, a group tries
        // its members in order - so every format gets per-repository routing behind this one controller. A plain
        // hosted repository (the common case, and the only case the free edition binds) declines the seam and
        // dispatches over its own store, keeping the deployment-wide format-level pull-through. Writes are never
        // routed here: a routed group deploy lands in its push-target member on the write path.
        if (isRead(request.getMethod()) && routed.routes(route.repository())) {
            Optional<RepositoryFormat> owner = dispatcher.owner(exchange.path());
            if (owner.isPresent()) {
                routed.serve(route.tenant(), route.repository(), owner.get(), exchange);
            } else {
                response.setStatus(404);
            }
            return;
        }
        if (!dispatcher.dispatch(exchange, route.store())) {
            response.setStatus(404);
        }
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    /**
     * The migration trigger only accepts {@code POST}; any other method on {@code /admin/import} (a {@code GET}
     * without a job id, say) is a {@code 405}, matching the headless dispatch. The more specific route wins over the
     * format catch-all, so a stray method is rejected here rather than falling through to a {@code 404}.
     */
    @RequestMapping(value = "/repository/admin/import", method = {RequestMethod.GET, RequestMethod.HEAD,
            RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public void importMethodNotAllowed(HttpServletResponse response) {
        response.setStatus(405);
    }

    /**
     * The admin trigger for a migration, asynchronous so the call returns at once: a small JSON body
     * ({@code {"source":"nexus|artifactory|maven|jenesis","url":...,"repository":...,"format":...,"username":...,
     * "password":...,"resume":...}}) starts a background job (see {@link ImportJobs}) and answers {@code 202} with
     * its id. The format ({@code maven}, {@code docker}, {@code raw}) is required for an Artifactory source and
     * optional for the others. A {@code resume} naming a prior job continues its walk from the recorded continuation
     * token and counts.
     */
    @PostMapping("/repository/admin/import")
    public void submitImport(@RequestBody(required = false) String body,
                             HttpServletRequest request,
                             HttpServletResponse response)
            throws IOException {
        if (readOnly()) {
            respond(response, 403, "this instance is in read-only mode: import is refused");
            return;
        }
        if (fetcher == ProxyFormat.Fetcher.NONE) {
            respond(response, 501, "no upstream fetcher module is installed on this deployment");
            return;
        }
        // The import writes into the same routed artifact space serving reads from, so a migrated artifact is
        // found where a later request looks for it; the job state rides along under that space's imports/ keys.
        ArtifactStore store = routing.route(request).store();
        ImportJobs jobs = new ImportJobs();
        JsonNode spec = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        String url = spec.path("url").asString(null);
        String repository = spec.path("repository").asString(null);
        if (url == null || repository == null) {
            respond(response, 400, "url and repository are required");
            return;
        }
        // SSRF screen: with the anonymous-possible default, an unguarded import URL turns this endpoint into a proxy
        // for the deployment's own network - a cloud metadata service (169.254.169.254), a loopback control plane
        // (127.0.0.1) or an internal host. Refuse a non-http(s) URL or one whose host resolves to a private, loopback,
        // link-local, site-local, multicast, CGNAT or unique-local address. On by default; an internal-host migration
        // opts out with jenesis.repository.block-private-import-hosts=false.
        if (blockPrivateImportHosts() && !isPublicImportUrl(url)) {
            respond(response, 400, "import url must be an http(s) URL to a public host; a private, loopback, "
                    + "link-local or cloud-metadata host is refused to prevent SSRF (set "
                    + "jenesis.repository.block-private-import-hosts=false to allow an internal-host migration)");
            return;
        }
        String resume = spec.path("resume").asString(null);
        ImportJobs.Snapshot prior = resume == null ? null : jobs.snapshot(store, resume).orElse(null);
        String cursor = prior == null ? null : prior.cursor();
        String sourceName = spec.path("source").asString(null);
        ImportRequest importRequest = new ImportRequest(URI.create(url), repository)
                .withFormat(spec.path("format").asString(null))
                .withCredentials(spec.path("username").asString(null), spec.path("password").asString(null))
                .withCursor(cursor);
        ImportSource source = importSources.stream()
                .filter(provider -> provider.handles(sourceName))
                .findFirst()
                .map(provider -> provider.create(importRequest, fetcher))
                .orElse(null);
        if (source == null) {
            respond(response, 400, "unknown import source, or its configuration is incomplete");
            return;
        }
        String jobId = prior == null ? ImportJobs.newId() : resume;
        jobs.submit(store, source, jobId, prior == null ? 0 : prior.imported(), prior == null ? 0 : prior.skipped());
        response.setHeader("Content-Type", "application/json");
        respond(response, 202, JSON.writeValueAsString(Map.of("job", jobId, "state", "running")));
    }

    /** Return a job's persisted state as raw JSON ({@code 404} if there is no such job). */
    @GetMapping("/repository/admin/import/{id}")
    public void importStatus(@PathVariable("id") String id,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        Optional<byte[]> state = new ImportJobs().status(routing.route(request).store(), id);
        if (state.isEmpty()) {
            response.setStatus(404);
            return;
        }
        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        try (OutputStream out = response.getOutputStream()) {
            out.write(state.get());
        }
    }

    /**
     * The paged asset enumeration - the free product's first {@code /api} surface and the outbound mirror of the
     * import connectors, so a jenesis instance can be walked by another tool (or another jenesis) and getting your
     * data out is never the paid feature. {@code GET /api/assets?repo=<name>&cursor=<token>&limit=<n>} returns a
     * flat, stably-ordered slice of the repository's published assets: each entry's {@code path}, {@code size} and
     * {@code sha256} come straight from the {@link build.jenesis.repository.store.Publication publication pointer}
     * (no blob is ever opened - read-first) and its {@code format}/{@code ecosystem}/{@code coordinate}/
     * {@code version} from the owning format's layout. The opaque {@code cursor} in the response fetches the next
     * page and is {@code null} once the walk is exhausted. {@code repo} defaults to the request's routed repository
     * and is validated as a traversal-free segment before it scopes the store; the wire is key-auth'd like every
     * other read ({@code repository:read}) by {@link RepositorySecurityAutoConfiguration}, which authorizes the
     * <em>effective</em> {@code repo} the store is scoped to (not merely the routed name) so this enumeration cannot
     * read a repository the key is not scoped for.
     */
    @GetMapping("/api/assets")
    public void assets(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RepositoryRouting.Route route = routing.route(request);
        String repository = request.getParameter("repo");
        if (repository == null || repository.isBlank()) {
            repository = route.repository();
        }
        if (!REPOSITORY.matcher(repository).matches()) {
            respond(response, 400, "repo must be a routable name matching " + REPOSITORY.pattern());
            return;
        }
        String after;
        String cursor = request.getParameter("cursor");
        if (cursor == null || cursor.isBlank()) {
            after = null;
        } else {
            try {
                after = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException _) {
                respond(response, 400, "malformed cursor");
                return;
            }
        }
        ArtifactStore store = root == null ? route.store() : root.scope(route.tenant()).scope(repository);
        AssetCatalog.Page page = new AssetCatalog(store, dispatcher::owner).page(after, pageSize(request.getParameter("limit")));
        List<Map<String, Object>> assets = new ArrayList<>();
        for (AssetCatalog.Asset asset : page.assets()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", asset.path());
            entry.put("size", asset.size());
            entry.put("sha256", asset.sha256());
            entry.put("format", asset.format());
            entry.put("ecosystem", asset.ecosystem());
            entry.put("coordinate", asset.coordinate());
            entry.put("version", asset.version());
            entry.put("prerelease", asset.prerelease());
            assets.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repository", repository);
        body.put("assets", assets);
        body.put("cursor", page.cursor() == null ? null
                : Base64.getUrlEncoder().withoutPadding().encodeToString(page.cursor().getBytes(StandardCharsets.UTF_8)));
        response.setHeader("Content-Type", "application/json");
        respond(response, 200, JSON.writeValueAsString(body));
    }

    private static int pageSize(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PAGE;
        }
        try {
            return Math.max(1, Math.min(MAX_PAGE, Integer.parseInt(value.trim())));
        } catch (NumberFormatException _) {
            return DEFAULT_PAGE;
        }
    }

    /**
     * Advertises the deployment-wide capabilities a client or console reads to adapt its behaviour - today the
     * read-only flag (so a console shows a banner and hides write affordances, and a mirror client knows writes are
     * refused) and whether the wire is credential-gated. Read like every other {@code /api} surface; a distribution
     * with more capabilities extends the map without a client change.
     */
    @GetMapping("/api/capabilities")
    public void capabilities(HttpServletResponse response) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("readOnly", readOnly());
        body.put("auth", Boolean.parseBoolean(settings.apply("auth")));
        response.setHeader("Content-Type", "application/json");
        respond(response, 200, JSON.writeValueAsString(body));
    }

    /** The deployment-wide read-only flag, read off the same {@code jenesis.repository.*} settings the formats read a
     *  toggle from, so no extra dependency is threaded in; unset means read-write. */
    private boolean readOnly() {
        return Boolean.parseBoolean(settings.apply("read-only"));
    }

    /** The import SSRF screen is on by default (the secure default); an internal-host migration opts out with
     *  {@code jenesis.repository.block-private-import-hosts=false}. Read off the same settings the read-only flag
     *  reads, so no extra dependency is threaded in - unset (or any value other than {@code false}) blocks. */
    private boolean blockPrivateImportHosts() {
        String value = settings.apply("block-private-import-hosts");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);
    }

    /** Whether an import URL is an {@code http(s)} URL to a host that is safe to reach: a public host, or one that
     *  does not resolve at all (unreachable, so not an SSRF vector - the import source's own probe then rejects it).
     *  A URL that is malformed, non-http(s), hostless, or resolves to any private/loopback/link-local/site-local/
     *  multicast/CGNAT/unique-local address is refused. */
    private static boolean isPublicImportUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException _) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException _) {
            // A host that does not resolve cannot be reached, so it is not an SSRF vector; let the import source's own
            // probe reject it (the documented "host that cannot answer" 400) rather than masking that here.
            return true;
        }
        for (InetAddress address : addresses) {
            if (isPrivateHost(address)) {
                return false;
            }
        }
        return true;
    }

    /** A private/loopback/link-local/site-local/multicast/CGNAT/unique-local address an import must not reach. The
     *  JDK classifiers cover loopback, wildcard, link-local (169.254/16, fe80::/10), site-local (10/8, 172.16/12,
     *  192.168/16) and multicast; CGNAT (100.64/10, RFC 6598) and IPv6 unique-local (fc00::/7, RFC 4193) are checked
     *  by hand as the JDK does not recognise them. */
    private static boolean isPrivateHost(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF, second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }
        return (bytes[0] & 0xFE) == 0xFC;
    }

    /** A write refused by the storage quota maps to {@code 507 Insufficient Storage} - the limit was hit before any
     *  bytes were stored, so this is a clean rejection the client can surface. */
    @ExceptionHandler(QuotaExceededException.class)
    public void quotaExceeded(QuotaExceededException exception, HttpServletResponse response) throws IOException {
        respond(response, 507, exception.getMessage());
    }

    /** A write refused because the deployment is read-only maps to {@code 403 Forbidden} - the store choke point
     *  rejected the mutation before any bytes were stored, whatever endpoint or internal path attempted it. */
    @ExceptionHandler(ReadOnlyException.class)
    public void readOnly(ReadOnlyException exception, HttpServletResponse response) throws IOException {
        respond(response, 403, exception.getMessage());
    }

    private static void respond(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
