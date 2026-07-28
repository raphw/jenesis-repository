package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.ScreenedDispatch;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The free ingress write edge ({@link ScreenedDispatch}) screens a claimed single-body write exactly once, before the
 * format lays it out, and leaves a {@code screened()==false} format's write unscreened. It drives the edge directly
 * over a real filesystem store with two spy formats - one a plain layout writer that does no screening of its own, one
 * that opts out of edge screening like OCI - and the discovered {@link CountingInterceptor} counts how often the chain
 * assesses a body, so "screened exactly once" and "bypassed" are asserted against a real count rather than inferred.
 */
public class ScreenedDispatchTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    /** A spy format: a pure layout writer that records what its handle received and how often it ran, and does no
     *  screening itself - so any screening a test observes came from the edge. */
    private static final class SpyFormat implements RepositoryFormat {

        private final String name;
        private final String prefix;
        private final boolean screened;
        private int writes;
        private byte[] received;

        private SpyFormat(String name, String prefix, boolean screened) {
            this.name = name;
            this.prefix = prefix;
            this.screened = screened;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith(prefix);
        }

        @Override
        public boolean screened() {
            return screened;
        }

        @Override
        public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
            if ("PUT".equals(exchange.method()) || "POST".equals(exchange.method())
                    || "PATCH".equals(exchange.method())) {
                try (InputStream in = exchange.requestStream()) {
                    received = in.readAllBytes();
                }
                writes++;
                exchange.respond(201);
            } else {
                exchange.respond(404);
            }
        }
    }

    /** A minimal {@link FormatExchange}: a request of a method/path/body, capturing the status the edge or format set. */
    private static final class FakeExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] body;
        private int status = -1;

        private FakeExchange(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
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
            return new ByteArrayInputStream(body);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return new ByteArrayOutputStream();
        }
    }

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        CountingInterceptor.reset();
    }

    private static ScreenedDispatch edge(RepositoryFormat... formats) {
        return new ScreenedDispatch(new FormatDispatcher(List.of(formats), Map.of(), ProxyFormat.Fetcher.NONE));
    }

    @Test
    void a_screened_put_is_screened_once_at_the_edge_then_laid_out() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/count-me/thing", "payload".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy).dispatch(put, store)).isTrue();

        assertThat(put.status).as("the format laid the accepted body out and set its own 201").isEqualTo(201);
        assertThat(spy.writes).as("the format's handle ran exactly once, over the restreamed blob").isEqualTo(1);
        assertThat(spy.received).as("the restreamed body is the screened bytes, byte for byte")
                .isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
        assertThat(CountingInterceptor.count()).as("the edge screened the body exactly once (the format did not re-screen)")
                .isEqualTo(1);
    }

    @Test
    void a_rejected_put_answers_422_and_never_reaches_the_format() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/count-me/gate-reject/x",
                "bad".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy).dispatch(put, store)).isTrue();

        assertThat(put.status).as("a rejected body is 422 at the edge").isEqualTo(422);
        assertThat(spy.writes).as("the format is never handed a rejected body - the edge screened before layout")
                .isZero();
    }

    @Test
    void a_quarantined_put_answers_202_and_is_not_laid_out() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/count-me/gate-quarantine/y",
                "hold".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy).dispatch(put, store)).isTrue();

        assertThat(put.status).as("a quarantined body is 202 at the edge").isEqualTo(202);
        assertThat(spy.writes).as("a quarantined body is held, never laid out").isZero();
    }

    @Test
    void an_unscreened_format_write_bypasses_the_edge_screen() throws IOException {
        SpyFormat oci = new SpyFormat("spybypass", "/spybypass/", false);
        FakeExchange put = new FakeExchange("PUT", "/spybypass/count-me/thing",
                "layer".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(oci).dispatch(put, store)).isTrue();

        assertThat(put.status).as("the format handled its own write").isEqualTo(201);
        assertThat(oci.writes).as("the format handled the write directly, unscreened").isEqualTo(1);
        assertThat(CountingInterceptor.count()).as("a screened()==false format's write never touches the edge screen")
                .isZero();
    }

    @Test
    void a_read_dispatches_unscreened() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange get = new FakeExchange("GET", "/spyscreened/count-me/thing", new byte[0]);

        assertThat(edge(spy).dispatch(get, store)).isTrue();

        assertThat(CountingInterceptor.count()).as("a read carries no body to screen").isZero();
        assertThat(spy.writes).isZero();
    }

    @Test
    void an_unclaimed_path_is_not_dispatched() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange put = new FakeExchange("PUT", "/nobody/claims/this", "x".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy).dispatch(put, store)).as("no format claimed the path, so the caller answers 404").isFalse();
        assertThat(spy.writes).isZero();
    }

    @Test
    void the_default_is_edge_screened_and_only_oci_opts_out() {
        RepositoryFormat plain = new RepositoryFormat() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public boolean handles(String path) {
                return false;
            }

            @Override
            public void handle(FormatExchange exchange, ArtifactStore store) {
            }
        };
        assertThat(plain.screened()).as("a format is edge-screened by default").isTrue();
        assertThat(RepositoryFormat.installed("oci").orElseThrow().screened())
                .as("only OCI opts out of the edge screen").isFalse();
    }
}
