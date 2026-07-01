package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

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
     * The upstream HTTP fetch, isolated behind an interface so a test answers from a fixed upstream without the
     * network. {@code requestHeaders} are sent upstream (e.g. {@code Accept} for OCI manifest negotiation, an
     * {@code Authorization} bearer token). An empty result is a transport failure; an HTTP error is a
     * {@link Fetched} carrying its status, so the adapter can act on a {@code 401} challenge or a {@code 404}.
     */
    @FunctionalInterface
    interface Fetcher {
        Optional<Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException;

        /**
         * Open a streaming download of a successful upstream {@code GET}, for an import that copies a large artifact
         * straight to storage rather than buffering it whole (as {@link #fetch} does for the proxy formats that must
         * inspect or rewrite a body). An empty result is a transport failure; a non-{@code 200} status is an
         * {@link IOException}. The caller owns and closes the returned stream. The default materializes from
         * {@link #fetch}; a real HTTP fetcher overrides it to stream the response body.
         */
        default Optional<InputStream> open(URI url, Map<String, String> requestHeaders) throws IOException {
            Optional<Fetched> fetched = fetch(url, requestHeaders);
            if (fetched.isEmpty()) {
                return Optional.empty();
            }
            Fetched response = fetched.get();
            if (response.status() != 200) {
                throw new IOException("Download failed (" + response.status() + ") for " + url);
            }
            return Optional.of(new ByteArrayInputStream(response.body()));
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
