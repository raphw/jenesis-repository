package build.jenesis.repository.server;

import module java.base;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The format-agnostic pull-through loop shared by every dispatcher. A {@code GET} of a path the format handles is
 * served locally first through a {@link Buffered} exchange that captures the response in memory; if that is a 404
 * the format's {@link ProxyFormat#proxy} adapter is given control to fetch from upstream, cache and serve - so a
 * later read is a local hit. A non-{@code GET} request, a local hit, or an adapter that declines passes straight
 * through (the 404 stands). The single network call sits behind {@link ProxyFormat.Fetcher} so the cache behaviour
 * is tested without the network.
 */
public final class PullThroughCache {

    private final ProxyFormat.Fetcher fetcher;

    public PullThroughCache(ProxyFormat.Fetcher fetcher) {
        this.fetcher = fetcher;
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
        Deferred deferred = new Deferred(exchange);
        format.handle(deferred, store);
        if (deferred.missed() && !proxy.proxy(exchange, store, upstream, fetcher)) {
            exchange.respond(404);
        }
    }

    /** The default upstream fetch over HTTP: request headers are forwarded; the status and response headers are
     *  returned. {@link ProxyFormat.Fetcher#download} is overridden to stream a download's body straight through
     *  rather than buffer it, so a large artifact (a proxied blob or an import) copies from the network to storage
     *  without materializing it; the caller acts on the status and closes the stream. */
    public static ProxyFormat.Fetcher http() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
                HttpRequest.Builder request = HttpRequest.newBuilder(url).GET();
                requestHeaders.forEach(request::header);
                try {
                    HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                    Map<String, String> headers = new LinkedHashMap<>();
                    response.headers().map().forEach((name, values) -> {
                        if (!values.isEmpty()) {
                            headers.put(name, values.getFirst());
                        }
                    });
                    return Optional.of(new ProxyFormat.Fetched(response.statusCode(), response.body(), headers));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while proxying " + url, e);
                }
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
                HttpRequest.Builder request = HttpRequest.newBuilder(url).GET();
                requestHeaders.forEach(request::header);
                try {
                    HttpResponse<InputStream> response = client.send(
                            request.build(), HttpResponse.BodyHandlers.ofInputStream());
                    Map<String, String> headers = new LinkedHashMap<>();
                    response.headers().map().forEach((name, values) -> {
                        if (!values.isEmpty()) {
                            headers.put(name, values.getFirst());
                        }
                    });
                    return Optional.of(new ProxyFormat.Download(response.statusCode(), response.body(), headers));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while proxying " + url, e);
                }
            }
        };
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
