package build.jenesis.repository.server;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.QuotaExceededException;
import build.jenesis.repository.store.ReadOnlyException;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import module java.base;

/**
 * The HTTP surface of the free repository, mirroring {@link RepositoryApplication}'s framework-neutral
 * dispatch but over Spring MVC. A catch-all resolves the request to its artifact space through {@link RepositoryRouting}
 * (fixed-tenant by default) and offers it the {@link RepositoryFormat} plugins over that doubly-scoped store through the
 * shared {@link FormatDispatcher}: the first format whose {@code handles(path)} is true serves or accepts the request
 * through a {@link ServletFormatExchange}; an unclaimed path is a {@code 404}. When an upstream is configured for the
 * matched format and the format is a {@link ProxyFormat}, a local miss is served through the {@link PullThroughCache}
 * from that upstream and cached, so a later read is a local hit. The single-tenant import edge
 * ({@code POST /repository/admin/import} and {@code GET /repository/admin/import/<id>}) is served by the separate
 * {@link ImportEdgeController} bean - peeled out (WFE.1) so a richer distribution can OWN the import edge through the
 * {@link ImportEdgeProvider} SPI without a cross-layer mapping override. Authorization is not done here:
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
    private final ScreenedDispatch screened;
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
        this(routing, dispatcher, importSources, fetcher, batch, settings, root, routed, EdgeHooks.NONE);
    }

    /** As above, threading an edition's {@link EdgeHooks} into the shared screening edge so a paid edition plugs its
     *  ingress concerns (tenant binding, a release-immutability {@code 409}, a quarantine-dispatch record, deploy
     *  observation) into the one free {@link ScreenedDispatch} rather than forking a second deploy controller.
     *  {@link EdgeHooks#NONE} (the default every convenience constructor above passes) is the free no-op, so the free
     *  edition's write choreography is byte-for-byte unchanged. */
    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher,
                                BatchIngestion batch,
                                UnaryOperator<String> settings,
                                ArtifactStore root,
                                RoutedServing routed,
                                EdgeHooks hooks) {
        this.routing = routing;
        this.dispatcher = dispatcher;
        this.screened = new ScreenedDispatch(dispatcher, hooks);
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
     * carrying the batch explode header is walked entry by entry by {@link BatchIngestion} - each member screened at
     * the same {@link ScreenedDispatch} ingress edge a single deploy uses - when the feature is enabled; otherwise the
     * header is inert and the body is a plain single upload.
     */
    @RequestMapping(value = {"/repository/**", "/v2", "/v2/**"}, method = {RequestMethod.GET, RequestMethod.HEAD,
            RequestMethod.PUT, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RepositoryRouting.Route route = routing.route(request);
        ServletFormatExchange exchange = new ServletFormatExchange(request, response, route.path(), settings);
        // A write (PUT/POST/PATCH/DELETE) to a route that is not a valid write target is a 405 before any layout - the
        // seam a multi-tenant routing uses to reject a write to a read-only repository. The fixed-tenant deployment
        // always resolves a writable route, so the free edition never takes this branch.
        if (isWrite(request.getMethod()) && !route.writable()) {
            response.setStatus(405);
            return;
        }
        if (batch != null && batch.claims(exchange)) {
            // Each exploded entry is screened at the same ingress edge a single deploy uses (the shared
            // ScreenedDispatch, carrying this controller's EdgeHooks), so a batch upload is screened exactly like a
            // series of individual deploys - one screening implementation, EdgeHooks and all.
            batch.explode(exchange, route.store(), screened);
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
        // A claimed single-body write (PUT/POST/PATCH on a screened() format) is screened at this ingress edge before
        // the format lays it out: the body is stored and the discovered interceptor chain runs once, then an accepted
        // blob is restreamed into the format for pure layout (QUARANTINE -> 202, REJECT -> 422). An unscreened format
        // (OCI) and every read/delete dispatch through the normal loop untouched. With the free edition's empty chain
        // this is byte-for-byte a direct dispatch; it carries the full ComplianceScreen chain under fixed tenancy.
        if (!screened.dispatch(exchange, route.store())) {
            response.setStatus(404);
        }
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    /** A mutating verb - the write that a non-writable route refuses with a {@code 405}. */
    private static boolean isWrite(String method) {
        return "PUT".equals(method) || "POST".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
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
     * with more capabilities extends the map without a client change - through the {@link CapabilityContributor} SPI
     * (below), not a bean override.
     *
     * <p>The base map ({@code readOnly}, {@code auth}, {@code anonymousRights}) is built here, then every
     * {@code ServiceLoader}-discovered {@link CapabilityContributor} is {@linkplain CapabilityContributor#merge merged}
     * into it: a richer distribution (the enterprise edition) contributes its formats / import-sources / module-flags
     * onto this one free-served endpoint by shipping a contributor module - the server already {@code uses} the SPI, so
     * no core change is needed. With no contributor installed (the free product) the served map is exactly the base map,
     * byte-for-byte unchanged. On a key conflict the base key wins (see {@link CapabilityContributor}'s merge rule), so a
     * contributor can only extend the free product's own flags, never shadow them.
     */
    @GetMapping("/api/capabilities")
    public void capabilities(HttpServletResponse response) throws IOException {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("readOnly", readOnly());
        base.put("auth", Boolean.parseBoolean(settings.apply("auth")));
        // WANON.1: advertise the strictly-opt-in anonymous role so a console shows an explicit "Anonymous access"
        // banner and a client knows keyless reads are served. Empty (the default) means no anonymous access. Read off
        // the same jenesis.repository.* settings the other flags read, so no extra dependency is threaded in.
        base.put("anonymousRights", anonymousRights());
        // WFE.1: collect the free-core CapabilityContributor SPI and merge each contribution onto the base map, so a
        // richer distribution extends the one free /api/capabilities without a bean override (retiring the enterprise
        // WebMvcRegistrations mapping-suppression stopgap). Base keys win a conflict; with no contributor the body is
        // the base map unchanged. Discovered per request through the same ServiceLoader seam the formats/import-sources
        // use, so a distribution plugs in with no core change.
        Map<String, Object> body = CapabilityContributor.merge(base, ServiceLoader.load(CapabilityContributor.class));
        response.setHeader("Content-Type", "application/json");
        respond(response, 200, JSON.writeValueAsString(body));
    }

    /** The deployment-wide read-only flag, read off the same {@code jenesis.repository.*} settings the formats read a
     *  toggle from, so no extra dependency is threaded in; unset means read-write. */
    private boolean readOnly() {
        return Boolean.parseBoolean(settings.apply("read-only"));
    }

    /** The strictly-opt-in anonymous-role grant (WANON.1) advertised on {@code /api/capabilities}, read off the same
     *  {@code jenesis.repository.*} settings; empty (the default) means no anonymous access. */
    private String anonymousRights() {
        String value = settings.apply("anonymous-rights");
        return value == null ? "" : value.trim();
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
