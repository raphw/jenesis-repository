package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

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
 *
 * <p>It is its own {@link ObservabilitySource}: the live fetcher the distribution holds reports {@code
 * jenesis.proxy.revalidation.bytes} - the cached index body bytes held, as a <em>bounded</em> gauge against the byte
 * ceiling past which the oldest entries are evicted, so the overview shows <em>data used vs available</em> and how
 * close the cache is to that ceiling without pre-computing a percentage - alongside {@code
 * jenesis.proxy.revalidation.entries} (the indexes currently remembered, bounded by the byte ceiling rather than a
 * fixed count) and a {@code jenesis.proxy.revalidation} health check that the cache is installed and saving
 * transfers. There is no background task (eviction happens lazily on the store path), so {@link #taskStatuses()}
 * stays empty.
 */
public final class RevalidatingFetcher implements ProxyFormat.Fetcher, ObservabilitySource {

    private static final long MAX_TOTAL = 64L * 1024 * 1024;
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private record Cached(byte[] body, Map<String, String> headers, String etag, String lastModified, long sequence) {
    }

    private final ProxyFormat.Fetcher delegate;
    private final ConcurrentMap<URI, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();
    // A monotonic stamp assigned to each entry as it is (re)fetched, so eviction can order by age: a ConcurrentHashMap
    // iterates its entrySet in hash-bucket order, which says nothing about which entry is oldest, so the sequence is
    // what makes "evict the oldest first" actually oldest-first rather than an arbitrary bucket walk.
    private final AtomicLong sequence = new AtomicLong();

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
                store(url, new Cached(response.body(), response.headers(), etag, lastModified,
                        sequence.incrementAndGet()));
            } else {
                // Drop any prior entry, subtracting its bytes from the running total: a bare cache.remove would leak
                // the accounting so `bytes` drifts permanently high and the eviction loop later evicts every fresh
                // entry, silently degrading revalidation to a pass-through.
                Cached previous = cache.remove(url);
                if (previous != null) {
                    bytes.addAndGet(-previous.body().length);
                }
            }
        }
        return fetched;
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        return delegate.download(url, requestHeaders);
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
                Metric.bounded("jenesis.proxy.revalidation.bytes",
                        "Cached proxied-index body bytes held for conditional revalidation, against the byte ceiling "
                                + "past which the oldest entries are evicted - a used-vs-available signal whose usage() "
                                + "fraction shows how close the cache is to thrashing back toward a full re-download.",
                        bytes.get(), MAX_TOTAL, "bytes"),
                Metric.gauge("jenesis.proxy.revalidation.entries",
                        "Proxied mutable indexes currently remembered with their ETag/Last-Modified validator, so a "
                                + "re-fetch is a conditional request whose 304 saves the transfer; bounded by the byte "
                                + "ceiling, not a fixed entry count.",
                        cache.size(), ""));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        return List.of(HealthCheck.up("jenesis.proxy.revalidation",
                "The revalidation cache is installed and saving index transfers by revalidating remembered bodies "
                        + "against the upstream."));
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
        if (total <= MAX_TOTAL) {
            return;
        }
        // Evict oldest-first: order a snapshot of the entries by their fetch sequence (ascending, so the
        // least-recently-refreshed lead) and drop from the front until the total is back under the ceiling. Iterating
        // the ConcurrentHashMap directly would evict in hash-bucket order - an arbitrary victim, not the oldest the
        // javadoc and the bytes gauge promise. The atomic remove(key, value) keeps the byte accounting exact even if
        // two threads evict concurrently: only a removal that actually took effect subtracts its bytes.
        List<Map.Entry<URI, Cached>> entries = new ArrayList<>(cache.entrySet());
        entries.sort(Comparator.comparingLong(entry -> entry.getValue().sequence()));
        Iterator<Map.Entry<URI, Cached>> victims = entries.iterator();
        while (bytes.get() > MAX_TOTAL && victims.hasNext()) {
            Map.Entry<URI, Cached> victim = victims.next();
            if (cache.remove(victim.getKey(), victim.getValue())) {
                bytes.addAndGet(-victim.getValue().body().length);
            }
        }
    }
}
