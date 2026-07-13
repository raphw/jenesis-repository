/**
 * The upstream HTTP connectivity as a plugin module: it {@code provides} a
 * {@link build.jenesis.repository.format.FetcherProvider} answering to {@code http}, composing the real HTTP
 * fetcher with index revalidation and negative caching of upstream misses - the machinery behind pull-through
 * proxying and repository imports. The dispatcher discovers it with {@code ServiceLoader} and names no transport;
 * a deployment without this module serves local content only (a proxy upstream is never consulted, an import is
 * refused). The composed caches are their own {@code ObservabilitySource}s reporting their bounded {@code
 * jenesis.proxy.*} used-vs-available signals, so it {@code requires} the equally minimal, registry-free
 * {@code build.jenesis.repository.observation} SPI beside the format SPI and {@code java.net.http}.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.proxy {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.observation;
    requires java.net.http;
    exports build.jenesis.repository.proxy;
    provides build.jenesis.repository.format.FetcherProvider
            with build.jenesis.repository.proxy.HttpFetcherProvider;
}
