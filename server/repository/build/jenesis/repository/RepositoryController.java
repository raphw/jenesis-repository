package build.jenesis.repository;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.source.ImportRequest;
import build.jenesis.repository.source.ImportSource;
import build.jenesis.repository.source.ImportSourceProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.QuotaExceededException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The HTTP surface of the free single-tenant repository, mirroring {@link RepositoryApplication}'s framework-neutral
 * dispatch but over Spring MVC. A catch-all offers every request the {@link RepositoryFormat} plugins (Maven/module,
 * OCI, raw) over the single store: the first format whose {@code handles(path)} is true serves or accepts the request
 * through a {@link ServletFormatExchange} carrying the full request path; an unclaimed path is a {@code 404}. When an
 * upstream is configured for the matched format and the format is a {@link ProxyFormat}, a local miss is served
 * through the {@link PullThroughCache} from that upstream and cached, so a later read is a local hit. {@code
 * /admin/import} triggers an asynchronous migration through the first {@link ImportSourceProvider} that handles the
 * requested source - discovered with {@code ServiceLoader} like the formats, so the server knows no incumbent by name -
 * run as a background {@link ImportJobs}, and {@code GET /admin/import/<id>} returns its state. Authorization is not done here:
 * {@link SecurityConfig} gates the wire through the {@link Authorization} credential model.
 */
@RestController
public class RepositoryController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<RepositoryFormat> formats;
    private final List<ImportSourceProvider> importSources;
    private final ArtifactStore store;
    private final Map<String, URI> upstreams;
    private final ProxyFormat.Fetcher fetcher;

    public RepositoryController(List<RepositoryFormat> formats,
                                List<ImportSourceProvider> importSources,
                                ArtifactStore store,
                                @Qualifier("upstreams") Map<String, URI> upstreams,
                                ProxyFormat.Fetcher fetcher) {
        this.formats = formats;
        this.importSources = importSources;
        this.store = store;
        this.upstreams = upstreams;
        this.fetcher = fetcher;
    }

    /**
     * The format catch-all: any request the {@code /admin/import} routes and the Actuator endpoints do not claim is
     * offered to the {@link RepositoryFormat} plugins over the store. More specific routes win in Spring, so this
     * only sees a format's own paths; an unclaimed one is a {@code 404}. A format with a configured upstream that is
     * a {@link ProxyFormat} serves a local miss through the {@link PullThroughCache}.
     */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.PUT,
            RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        for (RepositoryFormat format : formats) {
            if (format.handles(path)) {
                ServletFormatExchange exchange = new ServletFormatExchange(request, response, path);
                URI base = upstreams.get(format.name());
                if (base != null && format instanceof ProxyFormat proxy) {
                    new PullThroughCache(fetcher).serve(format, proxy, base, exchange, store);
                } else {
                    format.handle(exchange, store);
                }
                return;
            }
        }
        response.setStatus(404);
    }

    /**
     * The migration trigger only accepts {@code POST}; any other method on {@code /admin/import} (a {@code GET}
     * without a job id, say) is a {@code 405}, matching the headless dispatch. The more specific route wins over the
     * format catch-all, so a stray method is rejected here rather than falling through to a {@code 404}.
     */
    @RequestMapping(value = "/admin/import", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE})
    public void importMethodNotAllowed(HttpServletResponse response) {
        response.setStatus(405);
    }

    /**
     * The admin trigger for a migration, asynchronous so the call returns at once: a small JSON body
     * ({@code {"source":"nexus|artifactory","url":...,"repository":...,"format":...,"username":...,"password":...,
     * "resume":...}}) starts a background job (see {@link ImportJobs}) and answers {@code 202} with its id. The
     * format ({@code maven}, {@code docker}, {@code raw}) is required for an Artifactory source and optional for a
     * Nexus one. A {@code resume} naming a prior job continues its walk from the recorded continuation token and
     * counts.
     */
    @PostMapping("/admin/import")
    public void submitImport(@RequestBody(required = false) String body, HttpServletResponse response)
            throws IOException {
        ImportJobs jobs = new ImportJobs();
        JsonNode spec = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        String url = spec.path("url").asString(null);
        String repository = spec.path("repository").asString(null);
        if (url == null || repository == null) {
            respond(response, 400, "url and repository are required");
            return;
        }
        String resume = spec.path("resume").asString(null);
        ImportJobs.Snapshot prior = resume == null ? null : jobs.snapshot(store, resume).orElse(null);
        String cursor = prior == null ? null : prior.cursor();
        String sourceName = spec.path("source").asString(null);
        ImportRequest request = new ImportRequest(URI.create(url), repository)
                .withFormat(spec.path("format").asString(null))
                .withCredentials(spec.path("username").asString(null), spec.path("password").asString(null))
                .withCursor(cursor);
        ImportSource source = importSources.stream()
                .filter(provider -> provider.handles(sourceName))
                .findFirst()
                .map(provider -> provider.create(request, fetcher))
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
    @GetMapping("/admin/import/{id}")
    public void importStatus(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        Optional<byte[]> state = new ImportJobs().status(store, id);
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

    /** A write refused by the storage quota maps to {@code 507 Insufficient Storage} - the limit was hit before any
     *  bytes were stored, so this is a clean rejection the client can surface. */
    @ExceptionHandler(QuotaExceededException.class)
    public void quotaExceeded(QuotaExceededException exception, HttpServletResponse response) throws IOException {
        respond(response, 507, exception.getMessage());
    }

    private static void respond(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
