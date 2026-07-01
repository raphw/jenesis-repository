package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.bridge.MavenView;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis module layout ({@code /module/...} and {@code /artifact/...}): a {@code PUT} stores the blob
 * content-addressed through the core {@link Publication}, and a {@code GET} serves it. When a module jar is published,
 * it is cross-published into the Maven layout: this format hands the module to the {@link MavenView} the Maven format
 * provides (discovered with {@link ServiceLoader}), which gives it a Maven view - the jar under its derived coordinate
 * and a computed POM - so a Maven client reaches the same blob. The bridge is exposed only between the two Java
 * layouts and never on the public SPI; the core knows nothing of it.
 */
public final class JenesisFormat implements RepositoryFormat {

    private static final List<MavenView> MAVEN_VIEWS = ServiceLoader.load(MavenView.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    @Override
    public String name() {
        return "jenesis";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/module/") || path.startsWith("/artifact/");
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        if (exchange.method().equals("PUT")) {
            byte[] body = exchange.requestBytes();
            Publication publication = new Publication(store);
            publication.publish(path, body);
            String[] reference = JavaLayout.moduleReference(path);
            if (path.endsWith(".jar") && reference != null) {
                for (MavenView view : MAVEN_VIEWS) {
                    view.publish(reference[0], reference[1], body, publication);
                }
            }
            exchange.respond(201);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (new Publication(store).serve(path, buffer)) {
            exchange.respond(200, buffer.toByteArray());
        } else {
            exchange.respond(404);
        }
    }
}
