package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The framework-neutral core of the repository dispatch: it offers a {@link FormatExchange} to the
 * {@link RepositoryFormat} plugins over a scoped {@link ArtifactStore} and serves the first whose {@code handles(path)}
 * is true, either directly through {@link RepositoryFormat#handle} or, when an upstream is configured for that format
 * and the format is a {@link ProxyFormat}, through the {@link PullThroughCache} from that upstream. It holds no Spring
 * (or servlet) type, so both the Spring MVC {@link RepositoryController} and any other dispatcher (a multi-tenant
 * controller, a JDK-httpserver embedder) reuse the same loop rather than re-implementing it. The store
 * and the {@link FormatExchange#path() path} it matches on are already tenant-and-repository scoped by the caller (see
 * {@link RepositoryRouting}); this component only picks the format and drives it.
 */
public final class FormatDispatcher {

    private final List<RepositoryFormat> formats;
    private final Map<String, URI> upstreams;
    private final ProxyFormat.Fetcher fetcher;

    public FormatDispatcher(List<RepositoryFormat> formats, Map<String, URI> upstreams, ProxyFormat.Fetcher fetcher) {
        this.formats = formats;
        this.upstreams = upstreams;
        this.fetcher = fetcher;
    }

    /**
     * Offer the exchange to the first format that claims its {@link FormatExchange#path() path}, serving or accepting
     * the request against the scoped store. A format with a configured upstream that is a {@link ProxyFormat} serves a
     * local miss through the {@link PullThroughCache}. Returns {@code true} when a format claimed the path (the caller
     * has its response), or {@code false} when none did, so the caller answers a {@code 404}.
     */
    public boolean dispatch(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        for (RepositoryFormat format : formats) {
            if (format.handles(path)) {
                URI base = upstreams.get(format.name());
                if (base != null && fetcher != ProxyFormat.Fetcher.NONE && format instanceof ProxyFormat proxy) {
                    new PullThroughCache(fetcher).serve(format, proxy, base, exchange, store);
                } else {
                    format.handle(exchange, store);
                }
                return true;
            }
        }
        return false;
    }
}
