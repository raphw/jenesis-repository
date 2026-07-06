package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
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
            if (proxy.proxy(exchange, store, upstream, fetcher)) {
                observation.lowCardinalityKeyValue("outcome", "miss");
            } else {
                observation.lowCardinalityKeyValue("outcome", "negative");
                exchange.respond(404);
            }
            return null;
        });
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
