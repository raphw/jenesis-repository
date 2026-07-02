package build.jenesis.repository.importer.artifactory;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An {@link ImportSource} over a JFrog Artifactory instance, the read half of an Artifactory migration. It lists a
 * repository's files with the deep File List storage API ({@code GET /api/storage/<repo>?list&deep=1&listFolders=0}),
 * then downloads each from {@code <base>/<repo><uri>}. That API is an Artifactory Pro feature; against a free (OSS)
 * instance - which refuses it with {@code 400} - the walk falls back seamlessly to the OSS-available per-folder
 * Folder Info API ({@code GET /api/storage/<repo>/<path>}), recursed for the same file set. Unlike the Nexus
 * components API, the Artifactory listing does not carry a per-file format - a repository has a single package type -
 * so the format ({@code maven}, {@code docker}, {@code npm}, {@code pypi}, {@code nuget}, {@code gems}) is supplied
 * for the repository and reported for every asset. The network sits behind the same {@link ProxyFormat.Fetcher} the
 * proxy uses, so the walk is tested without an Artifactory.
 */
public final class ArtifactorySource implements ImportSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        if (page.status() == 200) {
            JsonNode body = JSON.readTree(new String(page.body(), StandardCharsets.UTF_8));
            for (JsonNode file : body.path("files")) {
                if (file.path("folder").asBoolean(false)) {
                    continue;
                }
                String uri = file.path("uri").asString(null);
                if (uri == null) {
                    continue;
                }
                String path = uri.startsWith("/") ? uri.substring(1) : uri;
                URI download = URI.create(prefix + URLEncoder.encode(repository, StandardCharsets.UTF_8) + "/" + path);
                consumer.accept(format, path, () -> open(download));
            }
        } else if (proGated(page)) {
            // A free (OSS) Artifactory gates the deep File List API behind Pro; the per-folder Folder Info API is
            // available, so walk it recursively for the same files - N requests instead of one.
            crawl(consumer, prefix, "");
        } else {
            throw new IOException("Artifactory listing failed (" + page.status() + ") for " + listing);
        }
        // both walks are a single pass with no mid-walk resume point: one terminal checkpoint.
        checkpoint.reached(null);
    }

    /** True when the deep File List API is refused specifically because it is an Artifactory Pro feature (a free
     *  instance answers {@code 400} with that message), as opposed to a genuine error that should surface. */
    private static boolean proGated(ProxyFormat.Fetched page) {
        return page.status() == 400
                && new String(page.body(), StandardCharsets.UTF_8).contains("available only in Artifactory Pro");
    }

    /** Walk the OSS-available Folder Info API ({@code GET /api/storage/<repo>/<path>}, its {@code children} one level
     *  deep), recursing into child folders and emitting each file - the free-Artifactory fallback that reconstructs
     *  the same {@code (format, path, download)} the deep File List would have yielded. */
    private void crawl(Asset consumer, String prefix, String path) throws IOException {
        URI folder = URI.create(prefix + "api/storage/" + URLEncoder.encode(repository, StandardCharsets.UTF_8)
                + (path.isEmpty() ? "" : "/" + path));
        ProxyFormat.Fetched page = get(folder);
        if (page.status() != 200) {
            throw new IOException("Artifactory folder listing failed (" + page.status() + ") for " + folder);
        }
        for (JsonNode child : JSON.readTree(new String(page.body(), StandardCharsets.UTF_8)).path("children")) {
            String uri = child.path("uri").asString(null);
            if (uri == null) {
                continue;
            }
            String name = uri.startsWith("/") ? uri.substring(1) : uri;
            String childPath = path.isEmpty() ? name : path + "/" + name;
            if (child.path("folder").asBoolean(false)) {
                crawl(consumer, prefix, childPath);
            } else {
                URI download = URI.create(prefix + URLEncoder.encode(repository, StandardCharsets.UTF_8)
                        + "/" + childPath);
                consumer.accept(format, childPath, () -> open(download));
            }
        }
    }

    private InputStream open(URI url) throws IOException {
        Map<String, String> headers = authorization == null ? Map.of() : Map.of("Authorization", authorization);
        ProxyFormat.Download download = fetcher.download(url, headers)
                .orElseThrow(() -> new IOException("No response from " + url));
        if (download.status() != 200) {
            download.close();
            throw new IOException("Download failed (" + download.status() + ") for " + url);
        }
        return download.body();
    }

    private ProxyFormat.Fetched get(URI url) throws IOException {
        Map<String, String> headers = authorization == null ? Map.of() : Map.of("Authorization", authorization);
        return fetcher.fetch(url, headers).orElseThrow(() -> new IOException("No response from " + url));
    }
}
