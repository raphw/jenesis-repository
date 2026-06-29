package build.jenesis.repository;

import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * The HTTP surface of the free single-tenant repository, mirroring {@link RepositoryServer#handle} and
 * {@link RepositoryServer} migration trigger but over Spring MVC. A catch-all offers every request the
 * {@link RepositoryFormat} plugins (Maven/module, OCI, raw) over the single store: the first format whose
 * {@code handles(path)} is true serves or accepts the request through a {@link ServletFormatExchange} carrying the
 * full request path; an unclaimed path is a {@code 404}. {@code /admin/import} triggers an asynchronous migration
 * off an incumbent manager (Nexus or Artifactory) through {@link ImportJobs}, and {@code GET /admin/import/<id>}
 * returns its state. Authorization is not done here: {@link SecurityConfig} gates the wire through the
 * {@link Authorization} credential model. The pull-through proxy ({@link PullThroughCache}) is a follow-up; this
 * controller dispatches hosted-only.
 */
@RestController
public class RepositoryController {

    private final List<RepositoryFormat> formats;
    private final ArtifactStore store;

    public RepositoryController(List<RepositoryFormat> formats, ArtifactStore store) {
        this.formats = formats;
        this.store = store;
    }

    /**
     * The format catch-all: any request the {@code /admin/import} routes and the Actuator endpoints do not claim is
     * offered to the {@link RepositoryFormat} plugins over the store. More specific routes win in Spring, so this
     * only sees a format's own paths; an unclaimed one is a {@code 404}.
     */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.PUT,
            RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        for (RepositoryFormat format : formats) {
            if (format.handles(path)) {
                format.handle(new ServletFormatExchange(request, response, path), store);
                return;
            }
        }
        response.setStatus(404);
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
        Map<String, Object> spec = Json.object(Json.parse(body == null || body.isBlank() ? "{}" : body));
        String url = Json.string(spec.get("url"));
        String repository = Json.string(spec.get("repository"));
        if (url == null || repository == null) {
            respond(response, 400, "url and repository are required");
            return;
        }
        String resume = Json.string(spec.get("resume"));
        ImportJobs.Snapshot prior = resume == null ? null : jobs.snapshot(store, resume).orElse(null);
        String cursor = prior == null ? null : prior.cursor();
        ImportSource source = importSource(Json.string(spec.get("source")), url, repository, spec, cursor);
        if (source == null) {
            respond(response, 400, "an Artifactory source needs a format, or the source is unknown");
            return;
        }
        String jobId = prior == null ? ImportJobs.newId() : resume;
        jobs.submit(store, source, jobId, prior == null ? 0 : prior.imported(), prior == null ? 0 : prior.skipped());
        response.setHeader("Content-Type", "application/json");
        respond(response, 202, "{\"job\":\"" + jobId + "\",\"state\":\"running\"}");
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

    private ImportSource importSource(String source, String url, String repository, Map<String, Object> spec,
                                      String cursor) {
        String username = Json.string(spec.get("username"));
        String password = Json.string(spec.get("password"));
        if ("artifactory".equals(source)) {
            String format = Json.string(spec.get("format"));
            if (format == null) {
                return null;
            }
            ArtifactorySource artifactory = new ArtifactorySource(URI.create(url), repository, format)
                    .withFetcher(PullThroughCache.http());
            return username == null || password == null ? artifactory : artifactory.withCredentials(username, password);
        }
        if (source == null || "nexus".equals(source)) {
            NexusSource nexus = new NexusSource(URI.create(url), repository).withFetcher(PullThroughCache.http());
            if (username != null && password != null) {
                nexus = nexus.withCredentials(username, password);
            }
            return cursor == null ? nexus : nexus.from(cursor);
        }
        return null;
    }

    private static void respond(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
