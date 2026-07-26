package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import io.micrometer.observation.ObservationRegistry;

/**
 * The format-agnostic pull-through loop shared by every dispatcher. A {@code GET} of a path the format handles is
 * served locally first through a {@link Buffered} exchange that captures the response in memory; if that is a 404
 * the format's {@link ProxyFormat#proxy} adapter is given control to fetch from upstream, cache and serve - so a
 * later read is a local hit. A non-{@code GET} request, a local hit, or an adapter that declines passes straight
 * through (the 404 stands). The single network call sits behind {@link ProxyFormat.Fetcher} so the cache behaviour
 * is tested without the network.
 *
 * <p>The pull-through edge is a screening ingress edge, the fourth alongside the deploy ({@link ScreenedDispatch}),
 * batch and import edges: EPIC 26 moved compliance screening off the formats' proxy legs (now layout-only) onto the
 * edges, and a proxied artifact must be screened before it is ever served (#79, the hardened serve-path fail-closed).
 * For a {@link RepositoryFormat#screened() screened} format the fetched artifact is cached content-addressed by the
 * format's own layout-only proxy leg and then screened here through the one {@link Publication#screen} seam the other
 * edges share - not a fourth copy of the choreography. On {@code ACCEPT} the cached blob is served from the store and
 * the after-commit observers fire; on {@code QUARANTINE}/{@code REJECT} the artifact is <em>withheld</em> - never
 * served and the serving pointer the proxy leg linked is withdrawn, so a rejected upstream artifact is never left
 * cached-and-served (the fail-closed contract {@link DemoSeeder} documents: "the inspectors screen the proxy leg").
 * The fetched body streams straight to the content-addressed store and the screen reads it back from there, so a
 * large artifact never lands whole in the heap. An {@link RepositoryFormat#screened() unscreened} format (OCI, with
 * its own manifest-time choke point) and a format's mutable index (a {@code maven-metadata.xml}, which carries no
 * artifact identity to screen) pass straight through as before.
 *
 * <p>Each proxy-eligible read is wrapped in a {@code jenesis.proxy.fetch} {@link Observations observation} tagged
 * with the {@code format} and the {@code outcome} - {@code hit} (served locally, no upstream call), {@code miss}
 * (fetched from upstream) or {@code negative} (upstream also missed) - so the upstream leg is visible in metrics,
 * logs and traces from one instrumentation point. Given an {@link ObservationRegistry#NOOP NOOP} registry (the
 * default constructor, and every test that builds this directly) the wrapper is inert.
 */
public final class PullThroughCache {

    private final ProxyFormat.Fetcher fetcher;
    private final ObservationRegistry observations;

    public PullThroughCache(ProxyFormat.Fetcher fetcher) {
        this(fetcher, ObservationRegistry.NOOP);
    }

    public PullThroughCache(ProxyFormat.Fetcher fetcher, ObservationRegistry observations) {
        this.fetcher = fetcher;
        this.observations = observations;
    }

    public void serve(RepositoryFormat format,
                      ProxyFormat proxy,
                      URI upstream,
                      FormatExchange exchange,
                      ArtifactStore store) throws IOException {
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            format.handle(exchange, store);
            return;
        }
        Observations.observe(observations, "jenesis.proxy.fetch", null, null, observation -> {
            observation.lowCardinalityKeyValue("format", format.name());
            Deferred deferred = new Deferred(exchange);
            format.handle(deferred, store);
            if (!deferred.missed()) {
                observation.lowCardinalityKeyValue("outcome", "hit");
                return null;
            }
            if (screens(format, exchange.path())) {
                // The screened proxy edge: let the format's layout-only proxy leg fetch and cache the upstream body
                // into the content-addressed store without its serve reaching the client (a Discarding exchange
                // swallows it), then screen the cached artifact and only serve/keep it on ACCEPT.
                if (!proxy.proxy(new Discarding(exchange), store, upstream, fetcher)) {
                    observation.lowCardinalityKeyValue("outcome", "negative");
                    exchange.respond(404);
                    return null;
                }
                observation.lowCardinalityKeyValue("outcome", "miss");
                serveScreened(format, exchange, store);
                return null;
            }
            if (proxy.proxy(exchange, store, upstream, fetcher)) {
                observation.lowCardinalityKeyValue("outcome", "miss");
                observePublish(format, exchange.path(), store);
            } else {
                observation.lowCardinalityKeyValue("outcome", "negative");
                exchange.respond(404);
            }
            return null;
        });
    }

    /** Whether the proxy leg for this path must be screened here at the edge: a {@link RepositoryFormat#screened()
     *  screened} format's cached artifact is, an unscreened format (OCI, whose manifest choke point screens its own
     *  proxy) is not, and neither is a format's mutable index - an {@link ArtifactLayout} whose {@link
     *  ArtifactLayout#describe describe} is empty (a {@code maven-metadata.xml}) carries no artifact identity to screen
     *  and is streamed fresh, uncached, so it passes straight through exactly as before. */
    private static boolean screens(RepositoryFormat format, String path) {
        if (!format.screened()) {
            return false;
        }
        return !(format instanceof ArtifactLayout layout) || layout.describe(path).isPresent();
    }

    /**
     * Screen the artifact the proxy leg just cached and serve it fail-closed. The blob is already stored
     * content-addressed under the pointer the layout-only proxy leg linked; it is screened through the one
     * {@link Publication#screen} seam (the deploy/batch/import edges share it) reading the stored blob back from the
     * store rather than buffering it. On {@code ACCEPT} the cached blob is served from the store through the format's
     * own {@link RepositoryFormat#handle} and the after-commit observers fire, exactly as a direct publish does. On
     * {@code QUARANTINE}/{@code REJECT} nothing is served: the screen has already diverted a quarantine to the
     * {@code /quarantine} view, and here the serving pointer the proxy leg linked is withdrawn so a read is a 404 and
     * the rejected upstream artifact is never left cached-and-served - the fail-closed serve of #79.
     */
    private void serveScreened(RepositoryFormat format, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        String path = exchange.path();
        Optional<String> hash = new Publication(store).blob(path);
        if (hash.isEmpty()) {
            // The proxy served without caching a pointer to screen (a mutable index slipping past the describe() guard);
            // there is no stored artifact to screen or withhold, so serve it straight through.
            format.handle(exchange, store);
            return;
        }
        ArtifactDescriptor described = descriptor(format, path);
        Publication.Published outcome;
        try (InputStream body = store.open("blobs/" + hash.get())) {
            outcome = new Publication(store).screen(described, body);
        }
        if (outcome.disposition() == PublishInterceptor.Disposition.ACCEPT) {
            format.handle(exchange, store);
            // T26.2 proxy-parity: fire the after-commit observers on the accepted, served proxy leg. This self-guards
            // on located() - so it never fires for a path a screen went on to withhold.
            observePublish(format, path, store);
            return;
        }
        // Fail-closed (#79): withdraw the serving pointer the proxy leg linked so the quarantined/rejected artifact is
        // never served and nothing stays cached-and-served, then answer the miss with a 404.
        new Publication(store).unpublish(path);
        exchange.respond(404);
    }

    /**
     * Fire the after-commit {@link build.jenesis.repository.store.PublicationObserver}s once a proxy leg has fetched,
     * stored and served an upstream miss, so a proxy-publish is observed exactly like a direct publish. Today the event
     * rides the format's embedded publish on the proxy path; as that embedded publish is retired the observer event
     * would otherwise be lost, so it is fired here at the point the fetched body is committed to the store. Best-effort
     * and contained: it fires only when the artifact is actually published ({@link Publication#located located} - so a
     * quarantined or rejected proxy leg is not observed) and any failure building the event is swallowed, never failing
     * the serve.
     */
    private static void observePublish(RepositoryFormat format, String path, ArtifactStore store) {
        try {
            Publication publication = new Publication(store);
            Optional<String> key = publication.located(path);
            if (key.isEmpty()) {
                return;
            }
            String hash = key.get().substring("blobs/".length());
            publication.published(descriptor(format, path).withBlob(hash, store.size(key.get())));
        } catch (Exception _) {
            // best-effort observer parity; a proxy serve must never fail because an observer event could not be built
        }
    }

    /** The claiming format's layout descriptor for the path when it has one, else a bare format-name-and-path
     *  descriptor - the neutral identity the observer keys on. */
    private static ArtifactDescriptor descriptor(RepositoryFormat format, String path) {
        if (format instanceof ArtifactLayout layout) {
            Optional<ArtifactDescriptor> described = layout.describe(path);
            if (described.isPresent()) {
                return described.get();
            }
        }
        return ArtifactDescriptor.at(format.name(), path);
    }

    /**
     * A {@link FormatExchange} that defers committing to the real exchange until it sees the format's status, so a
     * local hit streams its body straight to the client with nothing buffered, while a local {@code 404} is swallowed
     * (its tiny body discarded) and reported through {@link #missed()} so the loop can hand control to the proxy
     * adapter, which writes the real response itself. This works because a format always sets its status (and any
     * response headers) before it writes the body. Response headers are held until the commit; reads delegate to the
     * real exchange unchanged.
     */
    private static final class Deferred implements FormatExchange {

        private final FormatExchange delegate;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private boolean missed;

        private Deferred(FormatExchange delegate) {
            this.delegate = delegate;
        }

        @Override
        public String method() {
            return delegate.method();
        }

        @Override
        public String path() {
            return delegate.path();
        }

        @Override
        public String requestUri() {
            return delegate.requestUri();
        }

        @Override
        public String queryParameter(String name) {
            return delegate.queryParameter(name);
        }

        @Override
        public String requestHeader(String name) {
            return delegate.requestHeader(name);
        }

        @Override
        public String setting(String key) {
            return delegate.setting(key);
        }

        @Override
        public InputStream requestStream() throws IOException {
            return delegate.requestStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
            headers.put(name, value);
        }

        @Override
        public OutputStream respond(int status, long contentLength) throws IOException {
            if (status == 404) {
                missed = true;
                return OutputStream.nullOutputStream();
            }
            headers.forEach(delegate::setResponseHeader);
            return delegate.respond(status, contentLength);
        }

        private boolean missed() {
            return missed;
        }
    }

    /**
     * A {@link FormatExchange} that delegates every request-side read to the real exchange but silently discards the
     * response the proxy leg writes. The screened proxy edge lets a format fetch and cache the upstream body into the
     * content-addressed store through its layout-only proxy leg, but that leg's own serve must not reach the client
     * before the screen has passed - so it writes into this sink instead, and the edge serves the cached blob itself
     * once the screen accepts it. Nothing is buffered: a discarded body streams to {@link OutputStream#nullOutputStream}
     * a null sink, so a large artifact still streams network-to-store without landing in the heap.
     */
    private static final class Discarding implements FormatExchange {

        private final FormatExchange delegate;

        private Discarding(FormatExchange delegate) {
            this.delegate = delegate;
        }

        @Override
        public String method() {
            return delegate.method();
        }

        @Override
        public String path() {
            return delegate.path();
        }

        @Override
        public String requestUri() {
            return delegate.requestUri();
        }

        @Override
        public String queryParameter(String name) {
            return delegate.queryParameter(name);
        }

        @Override
        public String requestHeader(String name) {
            return delegate.requestHeader(name);
        }

        @Override
        public String setting(String key) {
            return delegate.setting(key);
        }

        @Override
        public InputStream requestStream() throws IOException {
            return delegate.requestStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
            // the proxy leg's response is discarded; the edge re-serves the accepted blob from the store itself
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            return OutputStream.nullOutputStream();
        }
    }
}
