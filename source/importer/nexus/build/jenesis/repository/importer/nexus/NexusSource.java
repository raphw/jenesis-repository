package build.jenesis.repository.importer.nexus;

import module java.base;
import build.jenesis.repository.format.PrivateHosts;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An {@link ImportSource} over a Sonatype Nexus 3 instance, the read half of a Nexus migration. It pages the
 * components REST API ({@code GET /service/rest/v1/components?repository=<name>}, followed by
 * {@code &continuationToken=<token>} until the token is absent), and for each component downloads every asset from
 * the {@code downloadUrl} the listing carries, handing it on with the component's {@code format} (Nexus names them
 * {@code maven2}, {@code docker}, {@code npm}, {@code pypi}, {@code nuget}, {@code rubygems}, {@code raw}). The
 * format is reported per asset, so a single Nexus instance with repositories of several formats migrates in one
 * pass and each asset reaches the importer for its ecosystem. The network sits behind the same
 * {@link ProxyFormat.Fetcher} the proxy uses, so the walk is tested without a Nexus.
 *
 * <p>Each {@code downloadUrl} is a semi-trusted absolute URL the listing chooses, fetched as an initial request the
 * fetcher's redirect-only SSRF screen never inspects, so a listing that aims a <em>cross-origin</em> download at a
 * private, loopback or cloud-metadata host is refused through the shared {@link PrivateHosts} screen before it is
 * fetched - the same guard the fetcher's redirect chain uses. A same-origin download goes exactly where the operator
 * already pointed the importer, so an on-premises Nexus's own private download URLs still resolve.
 */
public final class NexusSource implements ImportSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI base;
    private final String repository;
    private final ProxyFormat.Fetcher fetcher;
    private final String authorization;
    private final String cursor;

    public NexusSource(URI base, String repository, ProxyFormat.Fetcher fetcher) {
        this(base, repository, fetcher, null, null);
    }

    private NexusSource(URI base, String repository, ProxyFormat.Fetcher fetcher, String authorization, String cursor) {
        this.base = base;
        this.repository = repository;
        this.fetcher = fetcher;
        this.authorization = authorization;
        this.cursor = cursor;
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
            JsonNode body = JSON.readTree(page.body());   // parse straight off the bytes, no intermediate String copy
            for (JsonNode item : body.path("items")) {
                String format = item.path("format").asString(null);
                for (JsonNode asset : item.path("assets")) {
                    String path = asset.path("path").asString(null);
                    String downloadUrl = asset.path("downloadUrl").asString(null);
                    if (path != null && path.startsWith("/")) {
                        // Nexus 3.71+ (the H2/PostgreSQL datastore that replaced OrientDB) reports asset paths
                        // absolute, with a leading slash; the repository-relative path a store write needs - and the
                        // shape the OrientDB-era listing and the fixtures use - drops it. Normalise before safePath,
                        // whose empty-first-segment check would otherwise reject the whole asset (its traversal
                        // intent - ./ .. backslash - is untouched).
                        path = path.substring(1);
                    }
                    if (path == null || downloadUrl == null || !ImportSource.safePath(path)) {
                        continue;   // an incomplete entry, or a traversal-laced path no store write should see
                    }
                    URI download;
                    try {
                        download = URI.create(downloadUrl);
                    } catch (IllegalArgumentException malformed) {
                        continue;   // a download URL that is not even a URI is a broken listing entry, not an asset
                    }
                    // The download URL comes straight off the (semi-trusted) listing an incumbent Nexus serves, and is
                    // fetched as an INITIAL request - not a redirect - so HttpFetcher's redirect-only SSRF screen never
                    // sees it, and the import trigger only vetted the operator's base URL, not this per-asset URL. The
                    // SSRF vector is a listing that redirects the fetch to a DIFFERENT origin than the one the operator
                    // authorised - a compromised or misconfigured Nexus naming a cloud metadata service (169.254.169.254)
                    // or a foreign internal control plane. A CROSS-ORIGIN download at a private/loopback/metadata host is
                    // refused here through the shared PrivateHosts guard the redirect chain uses. A SAME-ORIGIN download
                    // is not screened: it goes exactly where the operator already pointed the importer, so an on-premises
                    // Nexus migration (base and assets both on an internal host, opted in at the edge with
                    // block-private-import-hosts=false) still resolves its own private download URLs.
                    if (!sameOrigin(download) && PrivateHosts.resolvesToPrivate(download.getHost())) {
                        continue;
                    }
                    consumer.accept(format, path, () -> open(download));
                }
            }
            token = body.path("continuationToken").asString(null);
            checkpoint.reached(token);
        } while (token != null);
    }

    private InputStream open(URI url) throws IOException {
        // The download URL comes off the listing, so the credentials travel only to the Nexus they belong to: a
        // cross-origin URL (a compromised or misconfigured instance) downloads unauthenticated instead of leaking
        // the operator's basic credentials to a third host - and a 401 then fails the import loudly.
        Map<String, String> headers = authorization == null || !sameOrigin(url)
                ? Map.of()
                : Map.of("Authorization", authorization);
        ProxyFormat.Download download = fetcher.download(url, headers)
                .orElseThrow(() -> new IOException("No response from " + url));
        if (download.status() != 200) {
            download.close();
            throw new IOException("Download failed (" + download.status() + ") for " + url);
        }
        return download.body();
    }

    private boolean sameOrigin(URI url) {
        return Objects.equals(base.getScheme(), url.getScheme())
                && Objects.equals(base.getRawAuthority(), url.getRawAuthority());
    }

    private ProxyFormat.Fetched get(URI url) throws IOException {
        Map<String, String> headers = authorization == null ? Map.of() : Map.of("Authorization", authorization);
        return fetcher.fetch(url, headers).orElseThrow(() -> new IOException("No response from " + url));
    }
}
