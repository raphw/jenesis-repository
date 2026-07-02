package build.jenesis.repository.importer;

import build.jenesis.repository.format.ProxyFormat;

/**
 * Discovers and builds an {@link ImportSource} for a named incumbent. A source implementation ships as its own module
 * that provides one of these; the server loads every provider with {@link java.util.ServiceLoader} and, for a
 * submitted migration, asks each whether it {@link #handles handles} the requested source, then has the first match
 * {@link #create build} the source from the request and the fetcher the server supplies. So the server carries no
 * knowledge of Nexus or Artifactory (or any future incumbent) - it only knows this contract, and a new one plugs in
 * by adding a module.
 */
public interface ImportSourceProvider {

    /** The stable source name this provider answers to (for example {@code "nexus"}, {@code "artifactory"}), so a
     *  console or client can enumerate the installed sources instead of hardcoding them. */
    String name();

    /** A human-readable label for pickers; the {@link #name() name} unless the provider overrides it. */
    default String label() {
        return name();
    }

    /** Whether a migration from this source must name an ecosystem format up front - a single-package-type
     *  incumbent (Artifactory, say) needs one, while a source that reports a format per asset does not. */
    default boolean requiresFormat() {
        return false;
    }

    /** Whether this provider builds sources for the given source name. */
    default boolean handles(String source) {
        return name().equals(source);
    }

    /** Build the source from the request, streaming through {@code fetcher}, or null when the request is missing
     *  something this source needs (an Artifactory source without an ecosystem format, say) - which the caller reports
     *  as a bad request. */
    ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher);
}
