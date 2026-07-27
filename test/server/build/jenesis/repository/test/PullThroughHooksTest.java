package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.PullThroughCache;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link PullThroughHooks} serve seam - the proxy-leg twin of {@link build.jenesis.repository.server.EdgeHooks}.
 * Drives {@link PullThroughCache#serve} directly over a spy {@link ProxyFormat} and a spy hooks, proving: the free
 * {@link PullThroughHooks#NONE} default serves a local hit byte-for-byte as before with no upstream touch; a spy hooks
 * sees {@code screenFetch} decorate the fetcher the miss leg invokes and {@code verifyHit} fire before a hit serves; a
 * {@link PullThroughHooks.HitDecision#withhold() withhold} decision answers {@code 404} without ever serving the local
 * bytes and without a miss-leg re-fetch; and a {@link PullThroughHooks.HitDecision#serveLocal serveLocal} decision hands
 * serving of the local bytes to the edition (the enterprise fail-closed re-screen), still no upstream fetch.
 */
public class PullThroughHooksTest {

    private static final byte[] HIT = "cached-locally".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPSTREAM = "from-the-upstream".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VERIFIED = "re-screened-local".getBytes(StandardCharsets.UTF_8);
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
    void none_serve_through_is_byte_identical_on_a_hit() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/a", HIT);
        FakeExchange exchange = new FakeExchange("GET", "/spyproxy/a");

        // The default constructor binds PullThroughHooks.NONE.
        new PullThroughCache(format.fetcher).serve(format, format, UPSTREAM_BASE, exchange, store);

        assertThat(exchange.status).isEqualTo(200);
        assertThat(exchange.responseBody).as("the local hit is served byte-for-byte").isEqualTo(HIT);
        assertThat(format.fetches.get()).as("a hit never touches the upstream").isZero();
    }

    @Test
    void a_spy_sees_screenFetch_decorate_the_miss_fetcher_and_verifyHit_fire_on_a_hit() throws IOException {
        SpyFormat format = new SpyFormat();
        format.upstream.put(UPSTREAM_BASE + "spyproxy/miss", UPSTREAM);
        SpyHooks hooks = new SpyHooks();

        // A miss: no local copy, the upstream has it. The screened fetcher must be the one the proxy leg invokes.
        FakeExchange miss = new FakeExchange("GET", "/spyproxy/miss");
        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, miss, store);

        assertThat(miss.status).isEqualTo(200);
        assertThat(miss.responseBody).isEqualTo(UPSTREAM);
        assertThat(hooks.screenFetchPaths).as("screenFetch is offered the miss path").containsExactly("/spyproxy/miss");
        assertThat(hooks.decoratedFetches.get()).as("the decorated fetcher is the one the miss leg invoked").isEqualTo(1);

        // A hit: verifyHit fires before serving, with the claiming format, path and scoped store.
        format.local.put("/spyproxy/hit", HIT);
        FakeExchange hit = new FakeExchange("GET", "/spyproxy/hit");
        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, hit, store);

        assertThat(hit.status).isEqualTo(200);
        assertThat(hit.responseBody).isEqualTo(HIT);
        assertThat(hooks.verifyHitPaths).as("verifyHit fires on the hit path").contains("/spyproxy/hit");
        assertThat(hooks.verifiedFormat).as("verifyHit receives the claiming format").isSameAs(format);
        assertThat(hooks.verifiedStore).as("verifyHit receives the scoped store").isSameAs(store);
        assertThat(hooks.screenFetchPaths).as("a hit runs no miss leg, so screenFetch is not offered the hit path")
                .doesNotContain("/spyproxy/hit");
    }

    @Test
    void a_withhold_decision_answers_404_without_serving_the_local_bytes() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/withheld", HIT);
        SpyHooks hooks = new SpyHooks();
        hooks.decision = PullThroughHooks.HitDecision.withhold();
        FakeExchange exchange = new FakeExchange("GET", "/spyproxy/withheld");

        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, exchange, store);

        assertThat(exchange.status).as("a withheld hit is a 404").isEqualTo(404);
        assertThat(exchange.responseBody).as("the local bytes are never served").isEmpty();
        assertThat(format.handles.get()).as("the format's local-first serve never ran").isZero();
        assertThat(format.fetches.get()).as("a withhold does not re-fetch via the miss leg").isZero();
    }

    @Test
    void a_serveLocal_decision_hands_serving_of_the_local_bytes_to_the_edition() throws IOException {
        SpyFormat format = new SpyFormat();
        format.local.put("/spyproxy/verify", HIT);
        SpyHooks hooks = new SpyHooks();
        // The edition serves the local bytes itself, fail-closed - here it re-screens and streams verified bytes.
        hooks.decision = PullThroughHooks.HitDecision.serveLocal(
                (fmt, ex, st) -> ex.respond(200, VERIFIED));
        FakeExchange exchange = new FakeExchange("GET", "/spyproxy/verify");

        new PullThroughCache(format.fetcher, hooks).serve(format, format, UPSTREAM_BASE, exchange, store);

        assertThat(exchange.status).isEqualTo(200);
        assertThat(exchange.responseBody).as("the edition served the (re-screened) local bytes").isEqualTo(VERIFIED);
        assertThat(format.handles.get()).as("the cache's own local-first serve did not run").isZero();
        assertThat(format.fetches.get()).as("a local re-verify never fetches upstream").isZero();
    }

    /** A spy format that is also its own {@link ProxyFormat}: a local map answers hits, an upstream map answers the
     *  proxy leg, and counters record how often it served locally and fetched upstream. */
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
            local.put(exchange.path(), body); // cache, so a later read is a local hit
            exchange.respond(200, body);
            return true;
        }
    }

    /** A spy {@link PullThroughHooks}: records every {@code verifyHit}/{@code screenFetch} call and the args it saw,
     *  wraps the fetcher with a counting decorator, and returns a configurable hit decision (serve-through by default). */
    private static final class SpyHooks implements PullThroughHooks {

        private final List<String> verifyHitPaths = new ArrayList<>();
        private final List<String> screenFetchPaths = new ArrayList<>();
        private final AtomicInteger decoratedFetches = new AtomicInteger();
        private RepositoryFormat verifiedFormat;
        private ArtifactStore verifiedStore;
        private HitDecision decision = HitDecision.serveThrough();

        @Override
        public HitDecision verifyHit(RepositoryFormat format, String path, ArtifactStore store) {
            verifyHitPaths.add(path);
            verifiedFormat = format;
            verifiedStore = store;
            return decision;
        }

        @Override
        public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream) {
            screenFetchPaths.add(path);
            return (url, headers) -> {
                decoratedFetches.incrementAndGet();
                return upstream.fetch(url, headers);
            };
        }
    }

    /** A minimal {@link FormatExchange} capturing the status and body a serve wrote. */
    private static final class FakeExchange implements FormatExchange {

        private final String method;
        private final String path;
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
