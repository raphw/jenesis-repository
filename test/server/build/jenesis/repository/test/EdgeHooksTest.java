package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.EdgeHooks;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.ScreenedDispatch;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.PublishInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link EdgeHooks} edge plug-in seam fires at the three points the enterprise edition plugs into - {@code
 * beforeLayout} (post-hash, pre-layout, where a {@link EdgeHooks.Refusal} short-circuits the enterprise
 * release-immutability {@code 409}), {@code held} (the quarantine branch's replay-context record) and {@code verdict}
 * (once per screened write, the deploy observation) - and a {@code beforeLayout} refusal short-circuits layout so the
 * format never lays the body out and no {@code published()} fires. The edge is driven directly over a real filesystem
 * store with a spy format and a spy hooks, reusing the T26.2 {@link ScreenedDispatchTest} scaffolding, and the
 * discovered {@link MarkerInterceptor} drives the {@code QUARANTINE}/{@code REJECT} verdicts off marker paths.
 */
public class EdgeHooksTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    /** A spy format: a pure layout writer recording how often its handle ran, so a test can assert layout happened
     *  (or, on a refusal, did not). */
    private static final class SpyFormat implements RepositoryFormat {

        private int writes;

        @Override
        public String name() {
            return "spyscreened";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/spyscreened/");
        }

        @Override
        public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
            try (InputStream in = exchange.requestStream()) {
                in.readAllBytes();
            }
            writes++;
            exchange.respond(201);
        }
    }

    /** A spy {@link EdgeHooks}: records every hook call in order and can be told to refuse a {@code beforeLayout}. */
    private static final class SpyHooks implements EdgeHooks {

        private final List<String> calls = new ArrayList<>();
        private Refusal refuseWith;
        private PublishInterceptor.Disposition lastVerdict;
        private String heldPath;
        private String heldHash;
        private boolean beforeLayoutSawLayout;
        private final SpyFormat format;

        private SpyHooks(SpyFormat format) {
            this.format = format;
        }

        @Override
        public Optional<Refusal> beforeLayout(RepositoryFormat format, ArtifactStore store,
                                              ArtifactDescriptor descriptor, String hash, FormatExchange exchange) {
            calls.add("beforeLayout");
            beforeLayoutSawLayout = this.format.writes > 0;
            return Optional.ofNullable(refuseWith);
        }

        @Override
        public void held(RepositoryFormat format, ArtifactStore store, String path, String hash,
                         FormatExchange exchange) {
            calls.add("held");
            heldPath = path;
            heldHash = hash;
        }

        @Override
        public void verdict(PublishInterceptor.Disposition disposition, ArtifactDescriptor descriptor,
                            FormatExchange exchange) {
            calls.add("verdict");
            lastVerdict = disposition;
        }
    }

    /** A minimal {@link FormatExchange}: a request of a method/path/body, capturing the status the edge or format set. */
    private static final class FakeExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] body;
        private int status = -1;
        private byte[] responseBody;

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
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    responseBody = toByteArray();
                }
            };
        }
    }

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ScreenedDispatch edge(RepositoryFormat format, EdgeHooks hooks) {
        return new ScreenedDispatch(new FormatDispatcher(List.of(format), Map.of(), ProxyFormat.Fetcher.NONE), hooks);
    }

    @Test
    void an_accepted_write_fires_beforeLayout_then_lays_out_then_verdict() throws IOException {
        SpyFormat spy = new SpyFormat();
        SpyHooks hooks = new SpyHooks(spy);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/thing", "payload".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy, hooks).dispatch(put, store)).isTrue();

        assertThat(put.status).as("the format laid the accepted body out").isEqualTo(201);
        assertThat(spy.writes).as("the format's handle ran once").isEqualTo(1);
        assertThat(hooks.calls).as("beforeLayout fires before layout, verdict once after")
                .containsExactly("beforeLayout", "verdict");
        assertThat(hooks.beforeLayoutSawLayout).as("beforeLayout runs before the format lays out").isFalse();
        assertThat(hooks.lastVerdict).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
    }

    @Test
    void a_beforeLayout_refusal_short_circuits_layout_and_returns_the_refusal_status() throws IOException {
        SpyFormat spy = new SpyFormat();
        SpyHooks hooks = new SpyHooks(spy);
        hooks.refuseWith = new EdgeHooks.Refusal(409, "release is immutable");
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/thing", "payload".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy, hooks).dispatch(put, store)).isTrue();

        assertThat(put.status).as("the refusal status is returned").isEqualTo(409);
        assertThat(new String(put.responseBody, StandardCharsets.UTF_8)).isEqualTo("release is immutable");
        assertThat(spy.writes).as("a refused write is never laid out - no published() (the format never ran)").isZero();
        assertThat(hooks.calls).as("beforeLayout refused, then the one verdict still fires")
                .containsExactly("beforeLayout", "verdict");
    }

    @Test
    void a_quarantined_write_fires_held_around_the_202() throws IOException {
        SpyFormat spy = new SpyFormat();
        SpyHooks hooks = new SpyHooks(spy);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/gate-quarantine/y",
                "hold".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy, hooks).dispatch(put, store)).isTrue();

        assertThat(put.status).as("a quarantined body is 202 at the edge").isEqualTo(202);
        assertThat(spy.writes).as("a quarantined body is not laid out").isZero();
        assertThat(hooks.calls).as("held fires on the quarantine branch, then verdict")
                .containsExactly("held", "verdict");
        assertThat(hooks.heldPath).isEqualTo("/spyscreened/gate-quarantine/y");
        assertThat(hooks.heldHash).as("the held hook receives the stored blob's hash").isNotBlank();
        assertThat(hooks.lastVerdict).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
    }

    @Test
    void a_rejected_write_fires_only_verdict() throws IOException {
        SpyFormat spy = new SpyFormat();
        SpyHooks hooks = new SpyHooks(spy);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/gate-reject/x", "bad".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy, hooks).dispatch(put, store)).isTrue();

        assertThat(put.status).as("a rejected body is 422 at the edge").isEqualTo(422);
        assertThat(spy.writes).isZero();
        assertThat(hooks.calls).as("no beforeLayout/held on a reject, only the one verdict")
                .containsExactly("verdict");
        assertThat(hooks.lastVerdict).isEqualTo(PublishInterceptor.Disposition.REJECT);
    }
}
