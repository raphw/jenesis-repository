package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactStore;

import java.io.IOException;
import java.util.Optional;

/**
 * A repository protocol over the shared {@link ArtifactStore}: it claims a set of request paths and serves and
 * accepts artifacts in one client ecosystem's wire format (Maven, OCI/Docker, npm, PyPI, NuGet). Formats are
 * discovered with {@link java.util.ServiceLoader}, exactly as the storage backends are, so a new ecosystem is a
 * module that depends only on this SPI and {@code provides RepositoryFormat} - it inherits the content-addressed
 * storage, multi-tenancy, authorization, retention and console without touching them, and a deployment plugs in
 * whichever layouts it wants. Implementations are stateless: the dispatcher passes the already
 * tenant-and-repository-scoped store on each call.
 */
public interface RepositoryFormat {

    /** A stable identifier for the format, e.g. {@code maven}, {@code oci}, {@code npm}. */
    String name();

    /** Whether this format owns the given request path (the repository prefix already stripped). */
    boolean handles(String path);

    /** Serve or accept the request against the scoped store, writing the response through the exchange. */
    void handle(FormatExchange exchange, ArtifactStore store) throws IOException;

    /**
     * The format's mark as a small SVG {@link IconResource} embedded in this module, or empty when it ships none.
     * The console renders it beside the format's repositories and browse rows, and a server icon endpoint serves it
     * (immutable, cached) with a neutral fallback for a format that returns empty. A {@code default} so no existing
     * format is forced to carry one and the core stays icon-agnostic; a format with an icon overrides this to
     * {@code Optional.of(IconResource.svg(...))}, drawing only from permissively-licensed icon sets (its source and
     * licence recorded next to the module).
     */
    default Optional<IconResource> icon() {
        return Optional.empty();
    }
}
