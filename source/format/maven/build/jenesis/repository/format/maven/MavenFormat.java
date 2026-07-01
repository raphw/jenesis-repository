package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Maven layout ({@code /maven/...}): a {@code PUT} stores the blob content-addressed through the shared
 * {@link Publication} store, and a {@code GET} serves it or generates {@code maven-metadata.xml} on read through
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
                publish(store, path, exchange.requestStream());
            }
            exchange.respond(201);
            return;
        }
        Optional<byte[]> generated = new MavenMetadata(store).serve(path);
        if (generated.isPresent()) {
            exchange.respond(200, generated.get());
            return;
        }
        Optional<String> key = new Publication(store).located(path);
        if (key.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (OutputStream out = exchange.respond(200, store.size(key.get()))) {
            store.read(key.get(), out);
        }
    }

    /** Store the artifact (streamed straight to storage), and if it is a modular jar, cross-publish its module view
     *  through the Jenesis layout - reading the module name back from the just-stored blob rather than buffering the
     *  jar in memory to inspect it. */
    static void publish(ArtifactStore store, String path, InputStream body) throws IOException {
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(body);
        publication.link(path, hash);
        String[] coordinate = JavaLayout.mavenCoordinate(path);
        if (!path.endsWith(".jar") || coordinate == null) {
            return;
        }
        String module;
        try (InputStream in = store.open("blobs/" + hash)) {
            module = JavaLayout.moduleName(in);
        }
        if (module == null) {
            return;
        }
        for (ModuleView view : MODULE_VIEWS) {
            view.publish(module, coordinate[2], hash, store);
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
        Optional<ProxyFormat.Download> fetched = fetcher.download(
                URI.create(root.endsWith("/") ? root + rest : root + "/" + rest), Map.of());
        if (fetched.isEmpty()) {
            return false;
        }
        try (ProxyFormat.Download download = fetched.get()) {
            if (download.status() != 200) {
                return false;
            }
            publish(store, path, download.body());
        }
        handle(exchange, store);
        return true;
    }
}
