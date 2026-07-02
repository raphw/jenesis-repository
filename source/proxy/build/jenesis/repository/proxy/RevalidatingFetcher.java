package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;

/**
 * A {@link ProxyFormat.Fetcher} decorator that revalidates a proxied mutable index against the upstream rather than
 * re-downloading it every time: it remembers a fetched body with its {@code ETag} / {@code Last-Modified} validator,
 * and on the next fetch of the same URL sends a conditional request ({@code If-None-Match} / {@code If-Modified-Since});
 * a {@code 304 Not Modified} serves the remembered body without its bytes crossing the wire again, and a {@code 200}
 * refreshes the entry. It never serves a stale body - the upstream is still asked every time, only the transfer is
 * saved - so it is safe without a freshness lifetime. Only a {@link #fetch} (a small, mutable index - a packument, a
 * metadata document, a version list) is revalidated; an immutable artifact is fetched through {@link #download} once
 * and then served from the local store, so {@code download} passes straight through and is never re-fetched. Keyed by
 * URL, bounded, and safe for concurrent use.
 */
public final class RevalidatingFetcher implements ProxyFormat.Fetcher {

    private static final long MAX_TOTAL = 64L * 1024 * 1024;
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private record Cached(byte[] body, Map<String, String> headers, String etag, String lastModified) {
    }

    private final ProxyFormat.Fetcher delegate;
    private final ConcurrentMap<URI, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();

    public RevalidatingFetcher(ProxyFormat.Fetcher delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        Cached cached = cache.get(url);
        Optional<ProxyFormat.Fetched> fetched = delegate.fetch(url, conditional(requestHeaders, cached));
        if (fetched.isEmpty()) {
            return fetched;
        }
        ProxyFormat.Fetched response = fetched.get();
        if (cached != null && response.status() == 304) {
            return Optional.of(new ProxyFormat.Fetched(200, cached.body(), cached.headers()));
        }
        if (response.status() == 200) {
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            if ((etag != null || lastModified != null) && response.body().length <= MAX_BODY) {
                store(url, new Cached(response.body(), response.headers(), etag, lastModified));
            } else {
                cache.remove(url);
            }
        }
        return fetched;
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        return delegate.download(url, requestHeaders);
    }

    private static Map<String, String> conditional(Map<String, String> requestHeaders, Cached cached) {
        if (cached == null) {
            return requestHeaders;
        }
        Map<String, String> headers = new LinkedHashMap<>(requestHeaders);
        if (cached.etag() != null) {
            headers.put("If-None-Match", cached.etag());
        }
        if (cached.lastModified() != null) {
            headers.put("If-Modified-Since", cached.lastModified());
        }
        return headers;
    }

    private void store(URI url, Cached cached) {
        Cached previous = cache.put(url, cached);
        long total = bytes.addAndGet(cached.body().length - (previous == null ? 0 : previous.body().length));
        Iterator<Map.Entry<URI, Cached>> victims = cache.entrySet().iterator();
        while (total > MAX_TOTAL && victims.hasNext()) {
            Map.Entry<URI, Cached> victim = victims.next();
            if (cache.remove(victim.getKey(), victim.getValue())) {
                total = bytes.addAndGet(-victim.getValue().body().length);
            }
        }
    }
}
