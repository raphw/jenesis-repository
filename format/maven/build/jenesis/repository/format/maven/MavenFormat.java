package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Maven layout ({@code /maven/...}): a {@code PUT} stores the blob content-addressed through the core
 * {@link Publication}, and a {@code GET} serves it or generates {@code maven-metadata.xml} on read through
 * {@link MavenMetadata} (a metadata upload is dropped, since the metadata is derived). When the uploaded artifact is a
 * modular jar, it is cross-published into the Jenesis module layout: this format reads the module name and hands it to
 * the {@link ModuleView} the Jenesis format provides (discovered with {@link ServiceLoader}), so a client resolving by
 * module name reaches the same blob - the bridge between the two layouts, exposed only between them and never on the
 * public SPI. Discovered like any other format; the core knows nothing of it.
 */
public final class MavenFormat implements RepositoryFormat, ProxyFormat {

    private static final List<ModuleView> MODULE_VIEWS = ServiceLoader.load(ModuleView.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/maven/");
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        if (exchange.method().equals("PUT")) {
            if (!MavenMetadata.isMetadataRequest(path)) {
                publish(store, path, exchange.requestBytes());
            }
            exchange.respond(201);
            return;
        }
        Optional<byte[]> generated = new MavenMetadata(store).serve(path);
        if (generated.isPresent()) {
            exchange.respond(200, generated.get());
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (new Publication(store).serve(path, buffer)) {
            exchange.respond(200, buffer.toByteArray());
        } else {
            exchange.respond(404);
        }
    }

    /** Store the artifact, and if it is a modular jar, cross-publish its module view through the Jenesis layout. */
    static void publish(ArtifactStore store, String path, byte[] body) throws IOException {
        Publication publication = new Publication(store);
        publication.publish(path, body);
        String[] coordinate = JavaLayout.mavenCoordinate(path);
        if (!path.endsWith(".jar") || coordinate == null) {
            return;
        }
        String module = JavaLayout.moduleName(body);
        if (module == null) {
            return;
        }
        for (ModuleView view : MODULE_VIEWS) {
            view.publish(module, coordinate[2], body, store);
        }
    }

    /**
     * Proxy a {@code /maven/} miss to the upstream Maven repository (Maven Central). Artifacts (jars, poms and their
     * checksums) are immutable and cached, and a cached modular jar is cross-published like a local one;
     * {@code maven-metadata.xml} is generated locally from the version folders, so it is not proxied.
     */
    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/maven/") || MavenMetadata.isMetadataRequest(path)) {
            return false;
        }
        String rest = path.substring("/maven/".length());
        String root = upstream.toString();
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(
                URI.create(root.endsWith("/") ? root + rest : root + "/" + rest), Map.of());
        if (fetched.isEmpty() || fetched.get().status() != 200) {
            return false;
        }
        publish(store, path, fetched.get().body());
        handle(exchange, store);
        return true;
    }
}
