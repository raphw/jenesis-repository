package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis module layout ({@code /module/...} and {@code /artifact/...}): a {@code PUT} stores the blob
 * content-addressed through the shared {@link Publication} store, and a {@code GET} serves it. A modular jar published
 * under the Maven layout is cross-published into this layout (its module view) by the Maven format, so it resolves by
 * module name; this format does not mirror the other way - a module published here stays in the module layout, and a
 * publisher that wants a Maven coordinate deploys under {@code /maven/} directly. The core knows nothing of it.
 */
public final class JenesisFormat implements RepositoryFormat {

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
        Publication publication = new Publication(store);
        if (exchange.method().equals("PUT")) {
            publication.link(path, publication.storeBlob(exchange.requestStream()));
            exchange.respond(201);
            return;
        }
        Optional<String> key = publication.located(path);
        if (key.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (OutputStream out = exchange.respond(200, store.size(key.get()))) {
            store.read(key.get(), out);
        }
    }
}
