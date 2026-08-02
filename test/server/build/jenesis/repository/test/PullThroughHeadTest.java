package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.PullThroughCache;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PullThroughCache#serve} admits {@code HEAD} into the same pull-through loop as {@code GET} - not the
 * straight-through non-{@code GET} path - so a {@code HEAD} runs the identical cache and gate semantics: a local hit
 * answers from the format (status and headers, no body), a miss hands to the proxy adapter, and a
 * {@link PullThroughHooks.HitDecision#withhold() withhold} gate answers {@code 404} without serving the local bytes and
 * without a miss-leg re-fetch. The withhold case is the security-bearing one: were {@code HEAD} demoted to the
 * non-{@code GET} branch it would reach {@code format.handle} directly, past {@code verifyHit}, and leak the existence
 * (a {@code 200} with headers) of a retracted artifact that a {@code GET} withholds. Driven directly over a spy
 * {@link ProxyFormat} and spy hooks, no network - the twin of {@link PullThroughHooksTest}, on the {@code HEAD} verb.
 */
public class PullThroughHeadTest {

    private static final byte[] HIT = "cached-locally".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPSTREAM = "from-the-upstream".getBytes(StandardCharsets.UTF_8);
    private static final URI UPSTREAM_BASE = URI.create("https://upstream.test/");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void a_head_hit_is_served_from_the_format_with_headers_and_status_but_no_body() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/a", HIT);
        FakeExchange head = new FakeExchange("HEAD", "/spyproxy/a");

        new PullThroughCache(format.fetcher).serve(format, format, UPSTREAM_BASE, head, store);

        assertThat(head.status).as("a HEAD hit is a 200").isEqualTo(200);
        assertThat(head.responseHeaders.get("Content-Length"))
                .as("the metadata headers a HEAD answers from are set")
                .isEqualTo(Integer.toString(HIT.length));
        assertThat(head.responseBody).as("a HEAD carries no body").isEmpty();
        assertThat(format.fetches.get()).as("a hit never touches the upstream").isZero();
    }

    @Test
    void a_head_is_admitted_to_the_pull_through_loop_so_verifyHit_gates_it_like_a_get() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/hit", HIT);
        SpyHooks hooks = new SpyHooks();
        FakeExchange head = new FakeExchange("HEAD", "/spyproxy/hit");

        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, head, store);

        assertThat(head.status).isEqualTo(200);
        assertThat(head.responseBody).isEmpty();
        assertThat(hooks.verifyHitPaths)
                .as("HEAD is admitted to the loop, so the hit gate fires just as it does for GET")
                .contains("/spyproxy/hit");
    }

    @Test
    void a_head_miss_hands_to_the_proxy_adapter() throws IOException {
        SpyFormat format = new SpyFormat();
        format.upstream.put(UPSTREAM_BASE + "spyproxy/miss", UPSTREAM);
        FakeExchange head = new FakeExchange("HEAD", "/spyproxy/miss");

        new PullThroughCache(format.fetcher).serve(format, format, UPSTREAM_BASE, head, store);

        assertThat(head.status).as("a HEAD miss is proxied and served").isEqualTo(200);
        assertThat(format.fetches.get()).as("the miss leg fetched from the upstream").isEqualTo(1);
    }

    @Test
    void a_withhold_decision_answers_404_on_a_head_without_leaking_the_artifact_or_refetching() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/withheld", HIT);
        SpyHooks hooks = new SpyHooks();
        hooks.decision = PullThroughHooks.HitDecision.withhold();
        FakeExchange head = new FakeExchange("HEAD", "/spyproxy/withheld");

        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, head, store);

        assertThat(head.status).as("a withheld HEAD is a 404, exactly as a withheld GET").isEqualTo(404);
        assertThat(head.responseBody).as("the local bytes are never served").isEmpty();
        assertThat(format.handles.get())
                .as("the format's own serve never ran - HEAD did not slip past the gate to handle")
                .isZero();
        assertThat(format.fetches.get()).as("a withhold does not re-fetch via the miss leg").isZero();
    }

    /** A spy format that is also its own {@link ProxyFormat}: a local map answers hits (HEAD-aware - status and headers
     *  only, no body), an upstream map answers the proxy leg, and counters record local serves and upstream fetches. */
    private static final class SpyFormat implements RepositoryFormat, ProxyFormat {

        private final Map<String, byte[]> local = new HashMap<>();
        private final Map<String, byte[]> upstream = new HashMap<>();
        private final AtomicInteger handles = new AtomicInteger();
        private final AtomicInteger fetches = new AtomicInteger();

        private final ProxyFormat.Fetcher fetcher = (url, headers) -> {
            fetches.incrementAndGet();
            byte[] body = upstream.get(url.toString());
            return Optional.of(body == null
                    ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        };

        @Override
        public String name() {
            return "spyproxy";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/spyproxy/");
        }

        @Override
        public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
            handles.incrementAndGet();
            byte[] body = local.get(exchange.path());
            if (body == null) {
                exchange.respond(404);
                return;
            }
            if (exchange.method().equals("HEAD")) {
                // HEAD answers from metadata: advertise the length, write no body.
                exchange.setResponseHeader("Content-Length", Integer.toString(body.length));
                exchange.respond(200, body.length).close();
                return;
            }
            exchange.respond(200, body);
        }

        @Override
        public boolean proxy(FormatExchange exchange, ArtifactStore store, URI base, ProxyFormat.Fetcher fetcher)
                throws IOException {
            Optional<ProxyFormat.Fetched> fetched =
                    fetcher.fetch(base.resolve(exchange.path().substring(1)), Map.of());
            if (fetched.isEmpty() || fetched.get().status() != 200) {
                return false;
            }
            byte[] body = fetched.get().body();
            local.put(exchange.path(), body);
            if (exchange.method().equals("HEAD")) {
                exchange.setResponseHeader("Content-Length", Integer.toString(body.length));
                exchange.respond(200, body.length).close();
                return true;
            }
            exchange.respond(200, body);
            return true;
        }
    }

    /** A spy {@link PullThroughHooks}: records every {@code verifyHit} path and returns a configurable hit decision
     *  (serve-through by default). */
    private static final class SpyHooks implements PullThroughHooks {

        private final List<String> verifyHitPaths = new ArrayList<>();
        private HitDecision decision = HitDecision.serveThrough();

        @Override
        public HitDecision verifyHit(RepositoryFormat format, String path, ArtifactStore store) {
            verifyHitPaths.add(path);
            return decision;
        }
    }

    /** A minimal {@link FormatExchange} capturing the method, status, response headers and body a serve wrote. */
    private static final class FakeExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private int status = -1;
        private byte[] responseBody = new byte[0];

        private FakeExchange(String method, String path) {
            this.method = method;
            this.path = path;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void setResponseHeader(String name, String value) {
            responseHeaders.put(name, value);
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    responseBody = toByteArray();
                }
            };
        }
    }
}
