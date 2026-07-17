package build.jenesis.repository.proxy;

import module java.base;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import build.jenesis.repository.format.ProxyFormat;

/**
 * The upstream fetch over HTTP: request headers are forwarded; the status and response headers are returned.
 * {@link ProxyFormat.Fetcher#download} is overridden to stream a download's body straight through rather than
 * buffer it, so a large artifact (a proxied blob or an import) copies from the network to storage without
 * materializing it; the caller acts on the status and closes the stream.
 *
 * <p>Every request is bounded by a per-request timeout on top of the connect timeout, so a stalled upstream - one
 * that accepts the connection but never sends a response - cannot hang a proxy read or an import forever. A timeout
 * is reported as the contract's transport failure (an empty result), so the proxy lets the local {@code 404} stand
 * and an import is refused rather than a {@code 5xx} escaping. The timeout bounds the whole exchange for a buffered
 * {@link #fetch} (a small mutable index) and the arrival of the response for a streaming {@link #download}; a large
 * artifact's body transfer is not clipped by it, and a body that ends short of its declared {@code Content-Length}
 * surfaces as an {@link IOException} on the read (buffered) or on the stream the caller copies into the store
 * (streamed), so a truncated response is never written as a complete cached artifact. The timeout defaults to a
 * minute and is overridable with the {@code jenesis.proxy.request-timeout} system property (ISO-8601 like
 * {@code PT30S}, or a plain number of seconds).
 *
 * <p>Redirects are followed manually rather than by the JDK client's automatic {@code NORMAL} policy, because that
 * policy re-sends every request header - including {@code Authorization} - to the redirect target even across a
 * change of host. An importer download (a Nexus, Artifactory or Jenesis asset URL taken off a listing) or a proxy
 * fetch may legitimately redirect to a different origin - a presigned object-store URL, a CDN - and the operator's
 * credentials must not travel there. So a redirect that leaves the original origin drops the sensitive headers, the
 * same way a browser or {@code docker} does; a same-origin redirect keeps them.
 */
public final class HttpFetcher implements ProxyFormat.Fetcher {

    /** Headers carrying a caller credential, dropped when a redirect crosses to another origin. */
    private static final Set<String> SENSITIVE = Set.of(
            "authorization", "proxy-authorization", "cookie", "jenesis-repository-key");

    /** A bound on the redirect chain, so a redirect loop cannot spin an import or a proxy fetch forever. */
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Duration requestTimeout;

    /** The default fetcher: a per-request timeout from {@code jenesis.proxy.request-timeout}, or one minute. */
    public HttpFetcher() {
        this(requestTimeout());
    }

    /** A fetcher with an explicit per-request timeout (the seam a test uses to drive a stalled upstream quickly). */
    public HttpFetcher(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        try {
            HttpResponse<byte[]> response = send(url, requestHeaders, HttpResponse.BodyHandlers.ofByteArray());
            return Optional.of(new ProxyFormat.Fetched(response.statusCode(), response.body(), headers(response)));
        } catch (HttpTimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        try {
            HttpResponse<InputStream> response = send(url, requestHeaders, HttpResponse.BodyHandlers.ofInputStream());
            return Optional.of(new ProxyFormat.Download(response.statusCode(), response.body(), headers(response)));
        } catch (HttpTimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    /** Issue the GET and follow redirects by hand, dropping the {@link #SENSITIVE} headers the moment the chain
     *  leaves the original origin so a caller credential never travels to a redirect target on another host. Both
     *  entry points are GET, so a redirect never has to reconsider the method. An intermediate redirect's body is
     *  closed before the next hop; the final response is returned with its body intact for the caller. */
    private <T> HttpResponse<T> send(URI url, Map<String, String> requestHeaders, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        URI origin = url;
        URI current = url;
        Map<String, String> headers = new LinkedHashMap<>(requestHeaders);
        for (int redirect = 0; ; redirect++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(current).timeout(requestTimeout).GET();
            headers.forEach(request::header);
            HttpResponse<T> response = client.send(request.build(), handler);
            Optional<String> location = redirect < MAX_REDIRECTS && isRedirect(response.statusCode())
                    ? response.headers().firstValue("Location")
                    : Optional.empty();
            if (location.isEmpty()) {
                return response;
            }
            if (response.body() instanceof Closeable body) {
                body.close(); // release the intermediate redirect's connection before the next hop
            }
            current = current.resolve(location.get());
            if (!sameOrigin(origin, current)) {
                headers.keySet().removeIf(name -> SENSITIVE.contains(name.toLowerCase(Locale.ROOT)));
            }
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** Same scheme, host and effective port - the origin a credential is scoped to. */
    private static boolean sameOrigin(URI left, URI right) {
        return Objects.equals(left.getScheme(), right.getScheme())
                && Objects.equals(left.getHost(), right.getHost())
                && port(left) == port(right);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    /** The configured per-request timeout: {@code jenesis.proxy.request-timeout} (ISO-8601 or plain seconds), or a minute. */
    private static Duration requestTimeout() {
        String value = System.getProperty("jenesis.proxy.request-timeout");
        if (value == null || value.isBlank()) {
            return Duration.ofSeconds(60);
        }
        String trimmed = value.trim();
        try {
            return Duration.parse(trimmed);
        } catch (DateTimeException _) {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        }
    }

    private static Map<String, String> headers(HttpResponse<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.getFirst());
            }
        });
        return headers;
    }
}
