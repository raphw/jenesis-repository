package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.PullThroughCache;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy pull-through edge is a screening ingress edge (EPIC 26's fourth edge, #79): a proxied artifact is screened
 * through the discovered {@link build.jenesis.repository.store.PublishInterceptor} chain <em>before</em> it is served,
 * and a non-{@code ACCEPT} verdict is withheld fail-closed - never served, and the serving pointer the layout-only
 * proxy leg linked is withdrawn so nothing stays cached-and-served. This drives the {@link PullThroughCache} directly
 * over a real filesystem store through a spy {@link ProxyFormat} that only lays its fetched body out (the EPIC 26
 * layout-only proxy contract, no screening of its own), against a fixed in-memory upstream (no network). It relies on
 * the same discovered test chain the other edge tests ride: {@link MarkerInterceptor} quarantines a
 * {@code gate-quarantine} coordinate and rejects a {@code gate-reject} one, and {@link RecordingObserver} records the
 * accepted-publish event for a {@code publish-observed} path - so "screened, then served or withheld" and "the
 * observers fire only on the accepted, served proxy leg" are asserted against real state. It restores the
 * {@link build.jenesis.repository.server.DemoSeeder} contract: the inspectors screen the proxy leg.
 */
class PullThroughScreenTest {

    private static final URI UPSTREAM = URI.create("https://upstream.test/");
    private static final byte[] CLEAN = "a clean upstream artifact".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BAD = "an old, benign-but-flagged artifact".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private ArtifactStore store;
    private final SpyProxyFormat format = new SpyProxyFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        RecordingObserver.reset();
    }

    private void serve(FormatExchange exchange, ProxyFormat.Fetcher fetcher) throws IOException {
        new PullThroughCache(fetcher).serve(format, format, UPSTREAM, exchange, store);
    }

    @Test
    void a_clean_proxied_artifact_is_screened_then_served_and_fires_published() throws IOException {
        String path = "/spyproxy/publish-observed/clean.bin";
        Fetcher fetcher = new Fetcher(Map.of("https://upstream.test/spyproxy/publish-observed/clean.bin", CLEAN));
        CapturingExchange get = new CapturingExchange("GET", path);

        serve(get, fetcher);

        assertThat(get.status()).as("a clean artifact pulls through and serves").isEqualTo(200);
        assertThat(get.bytes()).as("the served bytes are the accepted upstream body").isEqualTo(CLEAN);
        assertThat(new Publication(store).located(path)).as("the accepted artifact is cached for a later local hit")
                .isPresent();
        assertThat(fetcher.fetches()).as("the screen reads the cached blob from the store, it does not re-fetch")
                .isEqualTo(1);
        assertThat(RecordingObserver.published()).extracting(ArtifactDescriptor::path)
                .as("the after-commit observers fire on the accepted, served proxy leg (T26.2 parity)")
                .contains(path);
    }

    @Test
    void a_quarantined_proxied_artifact_is_withheld_and_never_served() throws IOException {
        String path = "/spyproxy/gate-quarantine/publish-observed/bad.bin";
        Fetcher fetcher = new Fetcher(
                Map.of("https://upstream.test/spyproxy/gate-quarantine/publish-observed/bad.bin", BAD));
        CapturingExchange get = new CapturingExchange("GET", path);

        serve(get, fetcher);

        assertThat(get.status()).as("a quarantined proxied artifact is not served - the read is a 404").isEqualTo(404);
        assertThat(get.bytes()).as("no artifact bytes ever reach the client").isEmpty();
        assertThat(new Publication(store).blob(path))
                .as("the serving pointer the proxy leg linked is withdrawn - nothing is left cached-and-served")
                .isEmpty();
        assertThat(store.readVersioned("publish/quarantine" + path))
                .as("the quarantine surface is populated (the demo gate contract)").isPresent();
        assertThat(RecordingObserver.published()).extracting(ArtifactDescriptor::path)
                .as("no published() event fires for a withheld proxy leg").doesNotContain(path);
    }

    @Test
    void a_rejected_proxied_artifact_is_withheld_and_leaves_no_pointer() throws IOException {
        String path = "/spyproxy/gate-reject/publish-observed/evil.bin";
        Fetcher fetcher = new Fetcher(
                Map.of("https://upstream.test/spyproxy/gate-reject/publish-observed/evil.bin", BAD));
        CapturingExchange get = new CapturingExchange("GET", path);

        serve(get, fetcher);

        assertThat(get.status()).as("a rejected proxied artifact is not served - the read is a 404").isEqualTo(404);
        assertThat(get.bytes()).as("no artifact bytes ever reach the client").isEmpty();
        assertThat(new Publication(store).blob(path))
                .as("the serving pointer the proxy leg linked is withdrawn").isEmpty();
        assertThat(store.readVersioned("publish/quarantine" + path))
                .as("a reject links nothing, not even a quarantine pointer").isEmpty();
        assertThat(RecordingObserver.published()).extracting(ArtifactDescriptor::path)
                .as("no published() event fires for a withheld proxy leg").doesNotContain(path);
    }

    @Test
    void an_upstream_miss_still_answers_a_plain_404() throws IOException {
        String path = "/spyproxy/publish-observed/absent.bin";
        Fetcher fetcher = new Fetcher(Map.of());
        CapturingExchange get = new CapturingExchange("GET", path);

        serve(get, fetcher);

        assertThat(get.status()).as("an upstream miss lets the 404 stand").isEqualTo(404);
        assertThat(new Publication(store).blob(path)).as("nothing is cached for an upstream miss").isEmpty();
    }

    /** A spy pull-through format: a pure layout writer (the EPIC 26 layout-only proxy contract) that fetches an
     *  upstream miss, stores it content-addressed and links its path, then serves it - and does no screening of its
     *  own, so any screening a test observes came from the {@link PullThroughCache} edge. Screened by default (not an
     *  {@code ArtifactLayout}), so every path it claims is screened at the edge. */
    private static final class SpyProxyFormat implements RepositoryFormat, ProxyFormat {

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
            Optional<String> key = new Publication(store).located(exchange.path());
            if (key.isEmpty()) {
                exchange.respond(404);
                return;
            }
            try (OutputStream out = exchange.respond(200, store.size(key.get()))) {
                store.read(key.get(), out);
            }
        }

        @Override
        public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
                throws IOException {
            String path = exchange.path();
            Optional<ProxyFormat.Download> fetched =
                    fetcher.download(URI.create(upstream.toString() + path.substring(1)), Map.of());
            if (fetched.isEmpty()) {
                return false;
            }
            try (ProxyFormat.Download download = fetched.get()) {
                if (download.status() != 200) {
                    return false;
                }
                Publication publication = new Publication(store);
                String hash = publication.storeBlob(download.body());
                publication.link(path, hash);
            }
            handle(exchange, store);
            return true;
        }
    }

    /** A fetcher answering a fixed upstream map by URL, counting its calls so a screen that reads the cached blob back
     *  from the store is proven not to re-fetch. */
    private static final class Fetcher implements ProxyFormat.Fetcher {

        private final Map<String, byte[]> upstream;
        private final AtomicInteger fetches = new AtomicInteger();

        private Fetcher(Map<String, byte[]> upstream) {
            this.upstream = upstream;
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
            fetches.incrementAndGet();
            byte[] body = upstream.get(url.toString());
            return Optional.of(body == null
                    ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        }

        private int fetches() {
            return fetches.get();
        }
    }

    /** A {@link FormatExchange} that captures the status and body a serve wrote, so a test asserts both what was served
     *  and - for a withheld artifact - that nothing was. */
    private static final class CapturingExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status = -1;

        private CapturingExchange(String method, String path) {
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
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return body;
        }

        private int status() {
            return status;
        }

        private byte[] bytes() {
            return body.toByteArray();
        }
    }
}
