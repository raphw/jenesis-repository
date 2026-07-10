package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactStore;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The optional pull-through capability of a {@link RepositoryFormat}: a format that also implements this serves a
 * local miss from an upstream repository, so the repository is a build's single front door rather than a
 * publish-only store. The adapter owns the whole upstream interaction through {@link #proxy} - it maps the request
 * to its upstream, fetches (with whatever request headers or auth the protocol needs, including a multi-step token
 * handshake), caches an immutable artifact and serves it, or rewrites and streams a mutable index. The dispatcher
 * only detects the local miss and hands control over; keeping this off the {@link RepositoryFormat} contract means
 * a hosted-only format is unaffected.
 */
public interface ProxyFormat {

    /**
     * Serve a local {@code GET} miss for a path this format handles from the upstream rooted at {@code upstream}.
     * The adapter fetches through {@code fetcher}, caches immutable artifacts (so a later read is a local hit) and
     * streams mutable indexes fresh (rewriting upstream links back to this repository where needed), writing the
     * response through {@code exchange}. Returns {@code true} when it served a response, or {@code false} to let
     * the local {@code 404} stand (an unproxyable path, or an upstream miss).
     */
    boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, Fetcher fetcher) throws IOException;

    /**
     * The canonical public upstream this format mirrors when a deployment enables proxying without naming one - the
     * Maven format's Maven Central, an npm format's registry.npmjs.org. A distribution takes its default upstream from
     * the format itself, so it needs no table of format names to know where each format proxies. Empty when the format
     * has no single well-known upstream; a deployment can always set one explicitly per format or per repository.
     */
    default Optional<URI> defaultUpstream() {
        return Optional.empty();
    }

    /**
     * Enumerate every artifact the upstream rooted at {@code upstream} publishes through this format's own
     * mirror-style index - the same PEP 503 project list, V3 catalog, {@code Packages} index or {@code repodata}
     * the format already reads to serve pull-through, pointed at "list everything" instead of "resolve one". The
     * stream is lazy (an index page is only read as the stream advances) and each {@link Coordinate} pairs the
     * layout path the artifact occupies under this format - the path this format's
     * {@link RepositoryImporter} accepts - with the upstream URL its bytes download from, so a vendor-neutral
     * migration walks any repository that speaks the format's own protocol, including another jenesis. The
     * default returns an empty stream: a format whose ecosystem publishes no walkable index (Conan exposes only a
     * search API) simply cannot enumerate, and a caller treats "nothing enumerated" as that format's honest
     * answer. Failures reading the initial index throw; a failure while the stream advances surfaces as an
     * {@link java.io.UncheckedIOException}.
     */
    default Stream<Coordinate> enumerate(Fetcher fetcher, URI upstream) throws IOException {
        return Stream.empty();
    }

    /** One enumerated artifact: the layout {@code path} it occupies under this format (no leading slash, the shape
     *  the format's {@link RepositoryImporter} accepts), the upstream {@code url} its bytes download from, and the
     *  request {@code headers} that download needs (the {@code Accept} an OCI manifest is negotiated with, say) -
     *  empty for a plain download. */
    record Coordinate(String path, URI url, Map<String, String> headers) {

        public Coordinate(String path, URI url) {
            this(path, url, Map.of());
        }
    }

    /**
     * The upstream HTTP fetch, isolated behind an interface so a test answers from a fixed upstream without the
     * network. {@code requestHeaders} are sent upstream (e.g. {@code Accept} for OCI manifest negotiation, an
     * {@code Authorization} bearer token). An empty result is a transport failure; an HTTP error is a
     * {@link Fetched} carrying its status, so the adapter can act on a {@code 401} challenge or a {@code 404}.
     */
    @FunctionalInterface
    interface Fetcher {

        /** The shared fetcher standing in when no upstream-fetcher module is installed: every fetch reports a
         *  transport failure. It is a singleton, so a dispatcher can tell "no upstream connectivity" by identity
         *  ({@code fetcher == Fetcher.NONE}) and skip proxying or refuse an import outright rather than failing
         *  request by request. */
        Fetcher NONE = (url, requestHeaders) -> Optional.empty();

        Optional<Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException;

        /**
         * Open a streaming download of an upstream {@code GET}, so a large artifact copies straight from the network
         * to storage rather than being buffered whole (as {@link #fetch} does for the small bodies a proxy must
         * inspect or rewrite). An empty result is a transport failure; otherwise the {@link Download} carries the
         * status and the body stream, so the caller acts on a non-{@code 200} itself - an import fails, a proxy lets
         * the local {@code 404} stand - rather than the fetcher throwing. The caller owns and closes the
         * {@link Download}. The default materializes from {@link #fetch}; a real HTTP fetcher overrides it to stream
         * the response body.
         */
        default Optional<Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
            return fetch(url, requestHeaders).map(response ->
                    new Download(response.status(), new ByteArrayInputStream(response.body()), response.headers()));
        }
    }

    /** A streaming upstream response: the HTTP status, the body stream (which the caller owns and closes, so a
     *  non-{@code 200} is closed without draining it), and the response headers - the latter for the content type and
     *  the auth challenge a streamed proxy fetch still has to read before it decides what to do with the body. */
    record Download(int status, InputStream body, Map<String, String> headers) implements Closeable {

        /** The first value of a response header, case-insensitively, or {@code null}. */
        public String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    /** An upstream response: the HTTP status, the body, and the response headers (for content type and auth challenges). */
    record Fetched(int status, byte[] body, Map<String, String> headers) {

        /** The first value of a response header, case-insensitively, or {@code null}. */
        public String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }
}
