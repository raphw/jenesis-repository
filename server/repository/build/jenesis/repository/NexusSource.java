package build.jenesis.repository;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;

/**
 * An {@link ImportSource} over a Sonatype Nexus 3 instance, the read half of a Nexus migration. It pages the
 * components REST API ({@code GET /service/rest/v1/components?repository=<name>}, followed by
 * {@code &continuationToken=<token>} until the token is absent), and for each component downloads every asset from
 * the {@code downloadUrl} the listing carries, handing it on with the component's {@code format} (Nexus names them
 * {@code maven2}, {@code docker}, {@code npm}, {@code pypi}, {@code nuget}, {@code rubygems}, {@code raw}). The
 * format is reported per asset, so a single Nexus instance with repositories of several formats migrates in one
 * pass and each asset reaches the importer for its ecosystem. The network sits behind the same
 * {@link ProxyFormat.Fetcher} the proxy uses, so the walk is tested without a Nexus.
 */
public final class NexusSource implements ImportSource {

    private final URI base;
    private final String repository;
    private final ProxyFormat.Fetcher fetcher;
    private final String authorization;
    private final String cursor;

    public NexusSource(URI base, String repository) {
        this(base, repository, PullThroughCache.http(), null, null);
    }

    private NexusSource(URI base, String repository, ProxyFormat.Fetcher fetcher, String authorization, String cursor) {
        this.base = base;
        this.repository = repository;
        this.fetcher = fetcher;
        this.authorization = authorization;
        this.cursor = cursor;
    }

    /** Walk through a supplied fetcher instead of the default HTTP client - the seam a test answers from a fake Nexus. */
    public NexusSource withFetcher(ProxyFormat.Fetcher fetcher) {
        return new NexusSource(base, repository, fetcher, authorization, cursor);
    }

    /** Authenticate the listing and downloads with HTTP basic credentials (a Nexus user and password or token). */
    public NexusSource withCredentials(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new NexusSource(base, repository, fetcher, "Basic " + token, cursor);
    }

    /** Resume the walk from a previously reported cursor (a continuation token) instead of from the first page. */
    public NexusSource from(String cursor) {
        return new NexusSource(base, repository, fetcher, authorization, cursor);
    }

    @Override
    public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
        String root = base.toString();
        String prefix = root.endsWith("/") ? root : root + "/";
        String token = cursor;
        do {
            URI url = URI.create(prefix + "service/rest/v1/components?repository="
                    + URLEncoder.encode(repository, StandardCharsets.UTF_8)
                    + (token == null ? "" : "&continuationToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8)));
            ProxyFormat.Fetched page = get(url);
            if (page.status() != 200) {
                throw new IOException("Nexus listing failed (" + page.status() + ") for " + url);
            }
            Map<String, Object> body = Json.object(Json.parse(new String(page.body(), StandardCharsets.UTF_8)));
            for (Object component : Json.array(body.get("items"))) {
                Map<String, Object> item = Json.object(component);
                String format = Json.string(item.get("format"));
                for (Object entry : Json.array(item.get("assets"))) {
                    Map<String, Object> asset = Json.object(entry);
                    String path = Json.string(asset.get("path"));
                    String downloadUrl = Json.string(asset.get("downloadUrl"));
                    if (path == null || downloadUrl == null) {
                        continue;
                    }
                    consumer.accept(format, path, () -> download(URI.create(downloadUrl)));
                }
            }
            token = Json.string(body.get("continuationToken"));
            checkpoint.reached(token);
        } while (token != null);
    }

    private byte[] download(URI url) throws IOException {
        ProxyFormat.Fetched fetched = get(url);
        if (fetched.status() != 200) {
            throw new IOException("Download failed (" + fetched.status() + ") for " + url);
        }
        return fetched.body();
    }

    private ProxyFormat.Fetched get(URI url) throws IOException {
        Map<String, String> headers = authorization == null ? Map.of() : Map.of("Authorization", authorization);
        return fetcher.fetch(url, headers).orElseThrow(() -> new IOException("No response from " + url));
    }
}
