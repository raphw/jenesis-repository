package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

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

    /**
     * Request paths this format suggests seeding a fresh, empty repository with, so an evaluator has real data to
     * look at - browse rows, a proxied artifact, and (when a coordinate is old and benign-but-vulnerable) a lit-up
     * vulnerability and quarantine surface. Each entry is a plain request path this format {@link #handles claims}
     * (e.g. a Maven jar under {@code /maven/...}), which the demo seeder fetches through the format's own pull-through
     * path - the normal pipeline, so the inspectors screen the proxy leg and the compliance gate populates itself; no
     * blob is embedded here. A {@code default} of nothing, so a format carries none unless it opts in and the demo
     * mode is a no-op for it; suggestions are best-effort over the public registries and never fetch actual malicious
     * bytes. The seeder only runs against a completely empty artifact space and only when the {@code demo} flag is on.
     */
    default List<String> demoArtifacts() {
        return List.of();
    }

    /** The config keys this format cannot run without; empty (the default) for every self-contained format. A
     *  format whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The installed format of the given {@link #name() name}, discovered via {@link ServiceLoader} from this SPI
     *  module - the sanctioned lookup for a neutral consumer (an importer walking a format's upstream index, say)
     *  that must find one format by name without carrying its own {@code uses} clause. Empty when no module on the
     *  path provides it, or when the format is configured off ({@code jenesis.repository.<name>=false},
     *  {@link Features}) - a disabled format degrades exactly like a missing module. */
    static Optional<RepositoryFormat> installed(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return Features.active(format.name(), format.requiredConfig()) ? Optional.of(format) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
