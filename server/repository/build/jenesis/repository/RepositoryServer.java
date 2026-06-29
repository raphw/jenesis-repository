package build.jenesis.repository;

import module java.base;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Right;

/**
 * Entry point and HTTP wiring for the dual-layout repository. A {@code PUT} stores the blob (content-addressed,
 * deduped) and publishes it under both the Maven and module views through {@link Publication}; a {@code GET}
 * resolves the request path to its blob. Each request is first cleared by the {@link Authorization}: a GET needs
 * {@code repository:read}, a PUT {@code repository:write}, against the optional {@code Jenesis-Repository-Name}
 * scope. A skeleton on the JDK HTTP server: the production build swaps this class for the Spring Boot stack,
 * while {@link Publication}, {@link Authorization}, {@link ModuleBridge} and {@link PomGenerator} carry over.
 */
public final class RepositoryServer {

    private final ArtifactStore store;
    private final List<RepositoryFormat> formats;
    private final Authorization authorization;
    private final Map<String, URI> upstreams;
    private final ProxyFormat.Fetcher fetcher;

    public RepositoryServer(ArtifactStore store) {
        this(store, loadFormats(), Authorization.anonymous(), Map.of(), PullThroughCache.http());
    }

    private RepositoryServer(ArtifactStore store,
                             List<RepositoryFormat> formats,
                             Authorization authorization,
                             Map<String, URI> upstreams,
                             ProxyFormat.Fetcher fetcher) {
        this.store = store;
        this.formats = formats;
        this.authorization = authorization;
        this.upstreams = upstreams;
        this.fetcher = fetcher;
    }

    /** The same server enforcing the given credential model instead of serving anonymously. */
    public RepositoryServer withAuthorization(Authorization authorization) {
        return new RepositoryServer(store, formats, authorization, upstreams, fetcher);
    }

    /**
     * The same server proxying a local miss to an upstream repository, keyed by format name (e.g. {@code raw} to a
     * file server). A format that also implements {@link ProxyFormat} maps the request upstream and caches what it
     * fetches, so a subsequent read is a local hit; a format without an entry, or one that is not a
     * {@link ProxyFormat}, stays hosted-only.
     */
    public RepositoryServer withProxy(Map<String, URI> upstreams) {
        return new RepositoryServer(store, formats, authorization, Map.copyOf(upstreams), fetcher);
    }

    /** The same proxy wiring against a fixed fetcher instead of the network, so the cache behaviour is testable. */
    public RepositoryServer withProxy(Map<String, URI> upstreams, ProxyFormat.Fetcher fetcher) {
        return new RepositoryServer(store, formats, authorization, Map.copyOf(upstreams), fetcher);
    }

    private static List<RepositoryFormat> loadFormats() {
        List<RepositoryFormat> formats = new ArrayList<>();
        ServiceLoader.load(RepositoryFormat.class).forEach(formats::add);
        return formats;
    }

    public static void main(String[] args) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(env("JENESIS_STORE", "filesystem"), RepositoryServer::env);
        int port = Integer.parseInt(env("JENESIS_PORT", "8080"));
        Authorization authorization = Boolean.parseBoolean(env("JENESIS_REPOSITORY_AUTH", "false"))
                ? Authorization.enforcing(store)
                : Authorization.anonymous();
        Running running = new RepositoryServer(store).withAuthorization(authorization).start(port);
        System.out.println("jenesis-repository listening on :" + running.port()
                + (authorization.enforced() ? " (enforcing credentials)" : " (anonymous)"));
    }

    private static String env(String key) {
        String value = System.getProperty(key);
        return value != null ? value : System.getenv(key);
    }

    private static String env(String key, String fallback) {
        String value = env(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public Running start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/healthz", exchange -> respond(exchange, 200, "ok"));
        server.createContext("/admin/import", this::imported);
        server.createContext("/", this::handle);
        server.start();
        return new Running(server);
    }

    /**
     * A handle on a started server: its actually bound port (so a caller can start on an ephemeral port 0 and
     * discover it) and an orderly shutdown. Keeps the JDK {@link HttpServer} private so callers - a test, say -
     * need not require {@code jdk.httpserver} to drive it.
     */
    public static final class Running implements AutoCloseable {

        private final HttpServer server;

        private Running(HttpServer server) {
            this.server = server;
        }

        public int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean write = !method.equals("GET") && !method.equals("HEAD");
        if (authorization.enforced()) {
            Authorization.Decision decision = authorization.authorize(
                    exchange.getRequestHeaders().getFirst("Jenesis-Repository-Key"),
                    exchange.getRequestHeaders().getFirst("Jenesis-Repository-Name"),
                    write ? Right.REPOSITORY_WRITE : Right.REPOSITORY_READ);
            if (decision == Authorization.Decision.UNAUTHORIZED) {
                respond(exchange, 401, "");
                return;
            }
            if (decision == Authorization.Decision.FORBIDDEN) {
                respond(exchange, 403, "");
                return;
            }
        }
        for (RepositoryFormat format : formats) {
            if (format.handles(path)) {
                URI base = upstreams.get(format.name());
                FormatExchange served = new ServerExchange(exchange);
                if (base != null && format instanceof ProxyFormat proxy) {
                    new PullThroughCache(fetcher).serve(format, proxy, base, served, store);
                } else {
                    format.handle(served, store);
                }
                return;
            }
        }
        respond(exchange, 404, "");
    }

    /**
     * The admin trigger for a migration, asynchronous so the call returns at once: {@code POST /admin/import} with a
     * small JSON body ({@code {"source":"nexus|artifactory","url":...,"repository":...,"format":...,"username":...,
     * "password":...,"resume":...}}) starts a background job (see {@link ImportJobs}) and answers {@code 202} with
     * its id; {@code GET /admin/import/<id>} returns the job's state and running counts. It is a bulk write, so a
     * submit needs the same {@code repository:write} right a {@code PUT} does (a status read needs only
     * {@code repository:read}); the format ({@code maven}, {@code docker}, {@code raw}) is required for an
     * Artifactory source and optional for a Nexus one. A {@code resume} naming a prior job continues its walk from
     * the recorded continuation token and counts.
     */
    private void imported(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean status = method.equals("GET") && path.startsWith("/admin/import/");
        if (!status && !(method.equals("POST") && path.equals("/admin/import"))) {
            respond(exchange, 405, "");
            return;
        }
        if (authorization.enforced()) {
            Authorization.Decision decision = authorization.authorize(
                    exchange.getRequestHeaders().getFirst("Jenesis-Repository-Key"),
                    exchange.getRequestHeaders().getFirst("Jenesis-Repository-Name"),
                    status ? Right.REPOSITORY_READ : Right.REPOSITORY_WRITE);
            if (decision == Authorization.Decision.UNAUTHORIZED) {
                respond(exchange, 401, "");
                return;
            }
            if (decision == Authorization.Decision.FORBIDDEN) {
                respond(exchange, 403, "");
                return;
            }
        }
        ImportJobs jobs = new ImportJobs();
        if (status) {
            Optional<byte[]> state = jobs.status(store, path.substring("/admin/import/".length()));
            if (state.isEmpty()) {
                respond(exchange, 404, "");
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, new String(state.get(), StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> spec = Json.object(Json.parse(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        String url = Json.string(spec.get("url"));
        String repository = Json.string(spec.get("repository"));
        if (url == null || repository == null) {
            respond(exchange, 400, "url and repository are required");
            return;
        }
        String resume = Json.string(spec.get("resume"));
        ImportJobs.Snapshot prior = resume == null ? null : jobs.snapshot(store, resume).orElse(null);
        String cursor = prior == null ? null : prior.cursor();
        ImportSource source = importSource(Json.string(spec.get("source")), url, repository, spec, cursor);
        if (source == null) {
            respond(exchange, 400, "an Artifactory source needs a format, or the source is unknown");
            return;
        }
        String jobId = prior == null ? ImportJobs.newId() : resume;
        jobs.submit(store, source, jobId, prior == null ? 0 : prior.imported(), prior == null ? 0 : prior.skipped());
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        respond(exchange, 202, "{\"job\":\"" + jobId + "\",\"state\":\"running\"}");
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
            ArtifactorySource artifactory = new ArtifactorySource(URI.create(url), repository, format).withFetcher(fetcher);
            return username == null || password == null ? artifactory : artifactory.withCredentials(username, password);
        }
        if (source == null || "nexus".equals(source)) {
            NexusSource nexus = new NexusSource(URI.create(url), repository).withFetcher(fetcher);
            if (username != null && password != null) {
                nexus = nexus.withCredentials(username, password);
            }
            return cursor == null ? nexus : nexus.from(cursor);
        }
        return null;
    }

    /** Adapts the JDK {@link HttpExchange} to the framework-neutral {@link FormatExchange} the formats speak. */
    private record ServerExchange(HttpExchange exchange) implements FormatExchange {

        @Override
        public String method() {
            return exchange.getRequestMethod();
        }

        @Override
        public String path() {
            return exchange.getRequestURI().getPath();
        }

        @Override
        public String queryParameter(String name) {
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw == null) {
                return null;
            }
            for (String pair : raw.split("&")) {
                int equals = pair.indexOf('=');
                String key = equals < 0 ? pair : pair.substring(0, equals);
                if (key.equals(name)) {
                    return URLDecoder.decode(equals < 0 ? "" : pair.substring(equals + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        @Override
        public InputStream requestStream() {
            return exchange.getRequestBody();
        }

        @Override
        public void setResponseHeader(String name, String value) {
            exchange.getResponseHeaders().add(name, value);
        }

        @Override
        public OutputStream respond(int status, long contentLength) throws IOException {
            exchange.sendResponseHeaders(status, contentLength);
            return exchange.getResponseBody();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
