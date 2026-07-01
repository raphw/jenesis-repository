package build.jenesis.repository.source.artifactory;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.source.ImportSource;
import build.jenesis.repository.source.Json;

/**
 * An {@link ImportSource} over a JFrog Artifactory instance, the read half of an Artifactory migration. It lists a
 * repository's files with the storage API ({@code GET /api/storage/<repo>?list&deep=1&listFolders=0}), then
 * downloads each from {@code <base>/<repo><uri>}. Unlike the Nexus components API, the Artifactory listing does not
 * carry a per-file format - a repository has a single package type - so the format ({@code maven}, {@code docker},
 * {@code npm}, {@code pypi}, {@code nuget}, {@code gems}) is supplied for the repository and reported for every
 * asset. The network sits behind the same {@link ProxyFormat.Fetcher} the proxy uses, so the walk is tested
 * without an Artifactory.
 */
public final class ArtifactorySource implements ImportSource {

    private final URI base;
    private final String repository;
    private final String format;
    private final ProxyFormat.Fetcher fetcher;
    private final String authorization;

    public ArtifactorySource(URI base, String repository, String format, ProxyFormat.Fetcher fetcher) {
        this(base, repository, format, fetcher, null);
    }

    private ArtifactorySource(URI base, String repository, String format,
                              ProxyFormat.Fetcher fetcher, String authorization) {
        this.base = base;
        this.repository = repository;
        this.format = format;
        this.fetcher = fetcher;
        this.authorization = authorization;
    }

    /** Authenticate the listing and downloads with HTTP basic credentials (an Artifactory user and password or token). */
    public ArtifactorySource withCredentials(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new ArtifactorySource(base, repository, format, fetcher, "Basic " + token);
    }

    @Override
    public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
        String root = base.toString();
        String prefix = root.endsWith("/") ? root : root + "/";
        URI listing = URI.create(prefix + "api/storage/"
                + URLEncoder.encode(repository, StandardCharsets.UTF_8) + "?list&deep=1&listFolders=0");
        ProxyFormat.Fetched page = get(listing);
        if (page.status() != 200) {
            throw new IOException("Artifactory listing failed (" + page.status() + ") for " + listing);
        }
        Map<String, Object> body = Json.object(Json.parse(new String(page.body(), StandardCharsets.UTF_8)));
        for (Object entry : Json.array(body.get("files"))) {
            Map<String, Object> file = Json.object(entry);
            if (Boolean.TRUE.equals(file.get("folder"))) {
                continue;
            }
            String uri = Json.string(file.get("uri"));
            if (uri == null) {
                continue;
            }
            String path = uri.startsWith("/") ? uri.substring(1) : uri;
            URI download = URI.create(prefix + URLEncoder.encode(repository, StandardCharsets.UTF_8) + "/" + path);
            consumer.accept(format, path, () -> download(download));
        }
        // the storage listing is a single response, so there is no mid-walk resume point: one terminal checkpoint.
        checkpoint.reached(null);
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
