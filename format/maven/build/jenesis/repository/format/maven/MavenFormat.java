package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.MavenMetadata;
import build.jenesis.repository.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Maven and jenesis-module format: the dual layout that is the repository's reason for being. It owns
 * {@code /maven/...}, {@code /module/...} and {@code /artifact/...}; a {@code PUT} stores the blob and
 * cross-publishes it under both views through {@link Publication}, and a {@code GET} serves the blob, or
 * generates {@code maven-metadata.xml} on read through {@link MavenMetadata}. A metadata upload is dropped, since
 * the metadata is derived. The first {@link RepositoryFormat}, now a plugin module of its own: it builds on the
 * shared Maven-layout primitives in the core but is discovered like any other format.
 */
public final class MavenFormat implements RepositoryFormat, ProxyFormat {

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/maven/") || path.startsWith("/module/") || path.startsWith("/artifact/");
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        if (exchange.method().equals("PUT")) {
            byte[] body = exchange.requestBytes();
            if (!MavenMetadata.isMetadataRequest(path)) {
                new Publication(store).publish(path, body);
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

    /**
     * Proxy a {@code /maven/} miss to the upstream Maven repository (Maven Central). Artifacts (jars, poms, modules
     * and their checksums) are immutable and cached; {@code maven-metadata.xml} is generated locally from the
     * version folders, so it is not proxied here. The module and artifact layouts are not proxied here.
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
        new Publication(store).publish(path, fetched.get().body());
        handle(exchange, store);
        return true;
    }
}
