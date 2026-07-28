package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis module layout ({@code /module/...} and {@code /artifact/...}): a {@code PUT} stores the blob
 * content-addressed through the shared {@link Publication} store, and a {@code GET} serves it. A modular jar published
 * under the Maven layout is cross-published into this layout (its module view) by the Maven format, so it resolves by
 * module name; this format does not mirror the other way - a module published here stays in the module layout, and a
 * publisher that wants a Maven coordinate deploys under {@code /maven/} directly. The core knows nothing of it.
 *
 * <p>It also carries the {@link ArtifactLayout} capability (detected with {@code instanceof}, exactly like the Maven
 * format, so it is additive - nothing on the {@link RepositoryFormat} contract changes): the module layout is the single
 * owner of its coordinate convention, so a coordinate-only consumer (download tracking, cleanup eviction, DNS/{@code
 * match=} routing) maps a {@code /module/} path to its neutral {@link ArtifactDescriptor} and back without hand-parsing
 * the layout. The coordinate is the module name; the versioned pointer {@code /module/<name>/<version>/<file>} carries
 * the version, the version-less latest pointer {@code /module/<name>/<name>.jar} carries none - the two link shapes
 * {@link ModuleViewPublisher} publishes.
 */
public final class JenesisFormat implements RepositoryFormat, ArtifactLayout {

    /** The package-ecosystem name the neutral descriptor carries - distinct from {@link #name()} "jenesis", the format
     *  id that routes the {@code /module/} and {@code /artifact/} paths. Any consumer of a Jenesis module reports the
     *  same ecosystem, whichever edition it runs in. */
    public static final String ECOSYSTEM = "Jenesis";

    @Override
    public String name() {
        return "jenesis";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/module/") || path.startsWith("/artifact/");
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        return descriptor(path);
    }

    @Override
    public List<String> paths(String coordinate, String version) {
        if (coordinate.isEmpty()) {
            return List.of();
        }
        // The two link shapes ModuleViewPublisher publishes for a module version: the version directory holding the
        // versioned jar (/module/<name>/<version>/<name>.jar), and the version-less latest pointer file
        // (/module/<name>/<name>.jar) - both pure functions of the coordinate, no artifact read, so a cleanup pass (or
        // a read-path navigation) enumerates and unpublishes exactly the pointers this coordinate version occupies.
        return List.of("/module/" + coordinate + "/" + version, "/module/" + coordinate + "/" + coordinate + ".jar");
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Both jenesis pointers are pure functions of the coordinate - unlike Maven, nothing here reads the store to
        // find a cross-published mirror - so the store overload is exactly the coordinate-only one.
        return paths(coordinate, version);
    }

    /** The neutral descriptor of a {@code /module/...} path, or empty when the path carries no coordinate to describe (a
     *  directory, an {@code /artifact/} blob, a non-jenesis path): a full {@code /module/<name>/<version>/<file>} maps
     *  to the module name + version, and the version-less latest pointer {@code /module/<name>/<name>.jar} to the module
     *  name with no version. This is the one place the {@code /module/} coordinate convention lives. */
    private static Optional<ArtifactDescriptor> descriptor(String path) {
        if (!path.startsWith("/module/")) {
            return Optional.empty();
        }
        String[] segments = path.substring("/module/".length()).split("/");
        if (segments.length == 3 && !segments[0].isEmpty() && !segments[1].isEmpty() && !segments[2].isEmpty()) {
            // /module/<name>/<version>/<file> - the versioned pointer.
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM, segments[0], segments[1], path, null, false, null, -1L));
        }
        if (segments.length == 2 && !segments[0].isEmpty() && segments[1].equals(segments[0] + ".jar")) {
            // /module/<name>/<name>.jar - the version-less latest pointer, described version-less.
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM, segments[0], null, path, null, false, null, -1L));
        }
        return Optional.empty();
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        Publication publication = new Publication(store);
        if (exchange.method().equals("PUT")) {
            // Layout-only (EPIC 26): screening rides the ingress edge, which screens the body to ACCEPT and restreams
            // the stored blob into this format, so this branch stores the body content-addressed (streamed, never
            // buffered) and links its path, then responds 201 - verdicts are the edge's business, not the format's.
            String hash = publication.storeBlob(exchange.requestStream());
            publication.link(path, hash);
            exchange.respond(201);
            return;
        }
        Optional<String> key = publication.located(path);
        if (key.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = store.size(key.get());
        if (exchange.method().equals("HEAD")) {
            // A HEAD is answered from the stored size (Content-Length), 200 with no body, without opening the blob -
            // the same HEAD-from-metadata contract OciFormat/RawFormat follow, rather than streaming the whole blob.
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200);
            return;
        }
        try (OutputStream out = exchange.respond(200, size)) {
            store.read(key.get(), out);
        }
    }
}
