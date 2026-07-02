package build.jenesis.repository.proxy;

import module java.base;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import build.jenesis.repository.format.ProxyFormat;

/**
 * The upstream fetch over HTTP: request headers are forwarded; the status and response headers are returned.
 * {@link ProxyFormat.Fetcher#download} is overridden to stream a download's body straight through rather than
 * buffer it, so a large artifact (a proxied blob or an import) copies from the network to storage without
 * materializing it; the caller acts on the status and closes the stream.
 */
public final class HttpFetcher implements ProxyFormat.Fetcher {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).GET();
        requestHeaders.forEach(request::header);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return Optional.of(new ProxyFormat.Fetched(response.statusCode(), response.body(), headers(response)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).GET();
        requestHeaders.forEach(request::header);
        try {
            HttpResponse<InputStream> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return Optional.of(new ProxyFormat.Download(response.statusCode(), response.body(), headers(response)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
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
