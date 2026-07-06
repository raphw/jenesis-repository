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
 */
public final class HttpFetcher implements ProxyFormat.Fetcher {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
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
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(requestTimeout).GET();
        requestHeaders.forEach(request::header);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
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
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(requestTimeout).GET();
        requestHeaders.forEach(request::header);
        try {
            HttpResponse<InputStream> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return Optional.of(new ProxyFormat.Download(response.statusCode(), response.body(), headers(response)));
        } catch (HttpTimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
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
