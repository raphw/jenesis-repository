package build.jenesis.repository.importer.index;

import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;

/**
 * Builds an {@link IndexSource} for an {@code "index"} migration - any server that publishes the requested
 * format's own mirror-style index, including another jenesis. Discovered by the server through
 * {@code ServiceLoader} over {@link ImportSourceProvider}. The ecosystem format is required up front (it names
 * whose index to walk); the provider resolves it among the installed formats through
 * {@link RepositoryFormat#installed(String)} and builds no source when the format is absent or does not proxy -
 * which the caller reports as a bad request, exactly like an unreachable host. Credentials ride as HTTP basic
 * auth on every index read and download, injected around the shared fetcher so the format's enumeration stays
 * credential-blind.
 */
public final class IndexSourceProvider implements ImportSourceProvider {

    @Override
    public String name() {
        return "index";
    }

    @Override
    public String label() {
        return "Format index";
    }

    @Override
    public boolean requiresFormat() {
        return true;
    }

    @Override
    public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
        if (request.format() == null) {
            return null;
        }
        RepositoryFormat format = RepositoryFormat.installed(request.format())
                .filter(candidate -> candidate instanceof ProxyFormat)
                .orElse(null);
        if (format == null) {
            return null;
        }
        ProxyFormat.Fetcher walker = request.username() != null && request.password() != null
                ? authorized(fetcher, request.username(), request.password())
                : fetcher;
        IndexSource source = new IndexSource(format, root(request), walker, request.cursor());
        return source.reachable() ? source : null;
    }

    /** The walk's root: the base URL with the repository appended as a path ({@code .} or blank when the URL
     *  already points at the index root), always with a trailing slash so index links resolve against it. */
    private static URI root(ImportRequest request) {
        StringBuilder url = new StringBuilder(request.url().toString());
        while (!url.isEmpty() && url.charAt(url.length() - 1) == '/') {
            url.setLength(url.length() - 1);
        }
        String path = request.repository() == null ? "" : request.repository();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.isEmpty() && !path.equals(".")) {
            url.append('/').append(path);
        }
        return URI.create(url.append('/').toString());
    }

    /** The shared fetcher with HTTP basic credentials injected on every fetch and download (unless a request
     *  already carries its own {@code Authorization}), so one wrapper authenticates whatever the format reads. */
    private static ProxyFormat.Fetcher authorized(ProxyFormat.Fetcher fetcher, String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        String authorization = "Basic " + token;
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
                return fetcher.fetch(url, merged(requestHeaders));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
                return fetcher.download(url, merged(requestHeaders));
            }

            private Map<String, String> merged(Map<String, String> requestHeaders) {
                if (requestHeaders.containsKey("Authorization")) {
                    return requestHeaders;
                }
                Map<String, String> merged = new HashMap<>(requestHeaders);
                merged.put("Authorization", authorization);
                return merged;
            }
        };
    }
}
