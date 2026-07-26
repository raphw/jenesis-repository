package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import io.micrometer.observation.ObservationRegistry;

/**
 * The format-agnostic pull-through loop shared by every dispatcher. A {@code GET} of a path the format handles is
 * served locally first through a {@link Buffered} exchange that captures the response in memory; if that is a 404
 * the format's {@link ProxyFormat#proxy} adapter is given control to fetch from upstream, cache and serve - so a
 * later read is a local hit. A non-{@code GET} request, a local hit, or an adapter that declines passes straight
 * through (the 404 stands). The single network call sits behind {@link ProxyFormat.Fetcher} so the cache behaviour
 * is tested without the network.
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
    private final PullThroughHooks hooks;

    public PullThroughCache(ProxyFormat.Fetcher fetcher) {
        this(fetcher, ObservationRegistry.NOOP);
    }

    public PullThroughCache(ProxyFormat.Fetcher fetcher, PullThroughHooks hooks) {
        this(fetcher, ObservationRegistry.NOOP, hooks);
    }

    public PullThroughCache(ProxyFormat.Fetcher fetcher, ObservationRegistry observations) {
        this(fetcher, observations, PullThroughHooks.NONE);
    }

    /**
     * Bind an edition's {@link PullThroughHooks} into the loop. The other constructors delegate here with
     * {@link PullThroughHooks#NONE} (the {@link EdgeHooks} convenience-constructor idiom), so an existing call site is
     * unchanged and serves byte-for-byte as before.
     */
    public PullThroughCache(ProxyFormat.Fetcher fetcher, ObservationRegistry observations, PullThroughHooks hooks) {
        this.fetcher = fetcher;
        this.observations = observations;
        this.hooks = hooks;
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
            // Consult the edition BEFORE the local-first serve, so a cached hit is verified against the current gate
            // before any byte is written. The free NONE hook returns serveThrough with no store read, so the hit path
            // below is byte-for-byte as before; the decision is made ahead of serving, never by wrapping the stream.
            PullThroughHooks.HitDecision decision = hooks.verifyHit(format, exchange.path(), store);
            if (decision instanceof PullThroughHooks.HitDecision.Withhold) {
                // A now-retracted/rejected artifact the current gate refuses: 404 without serving the local bytes and
                // without a miss-leg re-fetch (the caveat's "false must not dump to the miss leg").
                observation.lowCardinalityKeyValue("outcome", "withheld");
                exchange.respond(404);
                return null;
            }
            if (decision instanceof PullThroughHooks.HitDecision.ServeLocal serveLocal) {
                // The edition serves the local bytes itself, fail-closed over the local blob (no upstream fetch).
                observation.lowCardinalityKeyValue("outcome", "verified");
                serveLocal.serve().serve(format, exchange, store);
                return null;
            }
            // serveThrough (the free default): the local-first serve runs exactly as today.
            Deferred deferred = new Deferred(exchange);
            format.handle(deferred, store);
            if (!deferred.missed()) {
                observation.lowCardinalityKeyValue("outcome", "hit");
                return null;
            }
            if (proxy.proxy(exchange, store, upstream, hooks.screenFetch(exchange.path(), fetcher))) {
                observation.lowCardinalityKeyValue("outcome", "miss");
                observePublish(format, exchange.path(), store);
            } else {
                observation.lowCardinalityKeyValue("outcome", "negative");
                exchange.respond(404);
            }
            return null;
        });
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
}
