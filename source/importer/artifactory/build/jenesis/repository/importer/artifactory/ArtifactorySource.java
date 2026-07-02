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
 * Folder Info API ({@code GET /api/storage/<repo>/<path>}), recursed for the same file set and checkpointing after
 * each top-level subtree so an interrupted OSS migration resumes without re-walking it. Unlike the Nexus
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
    private final String cursor;

    public ArtifactorySource(URI base, String repository, String format, ProxyFormat.Fetcher fetcher) {
        this(base, repository, format, fetcher, null, null);
    }

    private ArtifactorySource(URI base, String repository, String format,
                              ProxyFormat.Fetcher fetcher, String authorization, String cursor) {
        this.base = base;
        this.repository = repository;
        this.format = format;
        this.fetcher = fetcher;
        this.authorization = authorization;
        this.cursor = cursor;
    }

    /** Authenticate the listing and downloads with HTTP basic credentials (an Artifactory user and password or token). */
    public ArtifactorySource withCredentials(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new ArtifactorySource(base, repository, format, fetcher, "Basic " + token, cursor);
    }

    /** Resume the OSS folder crawl after the top-level entry a prior walk last reported. The deep File List path is a
     *  single response, so it ignores the cursor. */
    public ArtifactorySource from(String cursor) {
        return new ArtifactorySource(base, repository, format, fetcher, authorization, cursor);
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
            // the deep listing is a single response, so there is no mid-walk resume point (the cursor is ignored).
            checkpoint.reached(null);
        } else if (proGated(page)) {
            // A free (OSS) Artifactory gates the deep File List API behind Pro; the per-folder Folder Info API is
            // available, so walk it recursively for the same files - N requests instead of one.
            crawlRepository(consumer, checkpoint, prefix);
        } else {
            throw new IOException("Artifactory listing failed (" + page.status() + ") for " + listing);
        }
    }

    /** True when the deep File List API is refused specifically because it is an Artifactory Pro feature (a free
     *  instance answers {@code 400} with that message), as opposed to a genuine error that should surface. */
    private static boolean proGated(ProxyFormat.Fetched page) {
        return page.status() == 400
                && new String(page.body(), StandardCharsets.UTF_8).contains("available only in Artifactory Pro");
    }

    /** Walk the repository over the OSS-available Folder Info API, importing each top-level entry's subtree in turn and
     *  reporting a checkpoint once it is fully consumed - so {@link #from(String)} resumes after the last completed
     *  top-level entry. The top-level entries are sorted, so the resume skip is deterministic regardless of the order
     *  Artifactory returns them (a finer, per-folder cursor is unnecessary for the small free repos this serves). */
    private void crawlRepository(Asset consumer, Checkpoint checkpoint, String prefix) throws IOException {
        URI rootFolder = URI.create(prefix + "api/storage/" + URLEncoder.encode(repository, StandardCharsets.UTF_8));
        ProxyFormat.Fetched page = get(rootFolder);
        if (page.status() != 200) {
            throw new IOException("Artifactory folder listing failed (" + page.status() + ") for " + rootFolder);
        }
        List<JsonNode> children = new ArrayList<>();
        for (JsonNode child : JSON.readTree(new String(page.body(), StandardCharsets.UTF_8)).path("children")) {
            if (name(child) != null) {
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(ArtifactorySource::name));
        for (JsonNode child : children) {
            String name = name(child);
            if (cursor != null && name.compareTo(cursor) <= 0) {
                continue;   // this top-level entry (and everything before it) was completed in a prior run
            }
            if (child.path("folder").asBoolean(false)) {
                crawl(consumer, prefix, name);
            } else {
                URI download = URI.create(prefix + URLEncoder.encode(repository, StandardCharsets.UTF_8) + "/" + name);
                consumer.accept(format, name, () -> open(download));
            }
            checkpoint.reached(name);   // the whole subtree is imported, so a resume can safely skip past it
        }
        checkpoint.reached(null);       // the walk is complete
    }

    /** Recurse a folder's subtree over the Folder Info API, emitting every file. No checkpoint - the resume cursor is
     *  reported at top-level-subtree granularity by {@link #crawlRepository}. */
    private void crawl(Asset consumer, String prefix, String path) throws IOException {
        URI folder = URI.create(prefix + "api/storage/"
                + URLEncoder.encode(repository, StandardCharsets.UTF_8) + "/" + path);
        ProxyFormat.Fetched page = get(folder);
        if (page.status() != 200) {
            throw new IOException("Artifactory folder listing failed (" + page.status() + ") for " + folder);
        }
        for (JsonNode child : JSON.readTree(new String(page.body(), StandardCharsets.UTF_8)).path("children")) {
            String name = name(child);
            if (name == null) {
                continue;
            }
            String childPath = path + "/" + name;
            if (child.path("folder").asBoolean(false)) {
                crawl(consumer, prefix, childPath);
            } else {
                URI download = URI.create(prefix + URLEncoder.encode(repository, StandardCharsets.UTF_8)
                        + "/" + childPath);
                consumer.accept(format, childPath, () -> open(download));
            }
        }
    }

    /** A child entry's name - its {@code uri} without the leading slash - or {@code null} if it carries none. */
    private static String name(JsonNode child) {
        String uri = child.path("uri").asString(null);
        return uri == null ? null : uri.startsWith("/") ? uri.substring(1) : uri;
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
