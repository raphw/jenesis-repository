package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;

/**
 * A {@link ProxyFormat.Fetcher} decorator that remembers an upstream {@code 404} for a short window, so the flood of
 * probes a build tool makes for artifacts that are not there upstream - a version range, a missing {@code SNAPSHOT},
 * an optional classifier, a {@code .sha256} a client guesses at - is answered from memory rather than re-hitting the
 * upstream every time, which otherwise multiplies load and risks the upstream's rate limit. Only a definite
 * {@code 404} is cached: a transport failure (empty result) or an auth challenge ({@code 401}/{@code 403}) is not,
 * being transient or resolvable, and any success passes through untouched. An entry expires after the configured
 * time-to-live, so a genuinely published artifact is seen within that window. It decorates both {@link #fetch} and
 * {@link #download} - Maven proxies through {@code download}, npm and the rest through {@code fetch} - keyed by URL
 * and safe for concurrent use, with a bounded map swept of expired entries when it fills.
 */
public final class NegativeCachingFetcher implements ProxyFormat.Fetcher {

    private static final int MAX_ENTRIES = 16_384;

    private final ProxyFormat.Fetcher delegate;
    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentMap<URI, Instant> misses = new ConcurrentHashMap<>();

    public NegativeCachingFetcher(ProxyFormat.Fetcher delegate, Duration ttl) {
        this(delegate, ttl, Clock.systemUTC());
    }

    /** The {@link Clock} seam lets a test advance time to assert an entry expires without sleeping. */
    public NegativeCachingFetcher(ProxyFormat.Fetcher delegate, Duration ttl, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        if (cached(url)) {
            return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
        }
        Optional<ProxyFormat.Fetched> fetched = delegate.fetch(url, requestHeaders);
        if (fetched.isPresent() && fetched.get().status() == 404) {
            record(url);
        }
        return fetched;
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        if (cached(url)) {
            return Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
        }
        Optional<ProxyFormat.Download> download = delegate.download(url, requestHeaders);
        if (download.isPresent() && download.get().status() == 404) {
            record(url);
        }
        return download;
    }

    private boolean cached(URI url) {
        Instant recordedAt = misses.get(url);
        if (recordedAt == null) {
            return false;
        }
        if (clock.instant().isBefore(recordedAt.plus(ttl))) {
            return true;
        }
        misses.remove(url, recordedAt);
        return false;
    }

    private void record(URI url) {
        if (misses.size() >= MAX_ENTRIES) {
            Instant now = clock.instant();
            misses.values().removeIf(recordedAt -> !now.isBefore(recordedAt.plus(ttl)));
        }
        misses.put(url, clock.instant());
    }
}
