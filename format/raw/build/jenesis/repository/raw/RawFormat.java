package build.jenesis.repository.raw;

import module java.base;
import build.jenesis.repository.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The generic (raw) format: a plain HTTP file store under {@code /raw/...}, for the artifacts that fit no package
 * ecosystem - installers, archives, datasets, signed binaries. A {@code PUT} stores the bytes content-addressed
 * through {@link Publication} (so a raw file that matches a jar, a tarball or an OCI layer dedupes to the one
 * {@code blobs/<sha256>}), a {@code GET} serves them, a {@code GET} on a trailing-slash path lists the directory,
 * and a {@code DELETE} removes the pointer. No metadata, no protocol - just the content-addressed store behind a
 * file API, so it is a thin plugin over the same primitives every other layout uses.
 */
public final class RawFormat implements RepositoryFormat, ProxyFormat {

    @Override
    public String name() {
        return "raw";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/raw/");
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        Publication publication = new Publication(store);
        String path = exchange.path();
        switch (exchange.method()) {
            case "PUT" -> {
                publication.link(path, publication.storeBlob(exchange.requestBytes()));
                exchange.respond(201);
            }
            case "DELETE" -> {
                publication.unpublish(path);
                exchange.respond(204);
            }
            case "HEAD" -> exchange.respond(publication.blob(path).isPresent() ? 200 : 404);
            default -> {
                if (path.endsWith("/")) {
                    listing(path, store, exchange);
                    return;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                if (publication.serve(path, buffer)) {
                    exchange.setResponseHeader("Content-Type", "application/octet-stream");
                    exchange.respond(200, buffer.toByteArray());
                } else {
                    exchange.respond(404);
                }
            }
        }
    }

    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/raw/") || path.endsWith("/")) {
            return false;
        }
        String rest = path.substring("/raw/".length());
        String root = upstream.toString();
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(
                URI.create(root.endsWith("/") ? root + rest : root + "/" + rest), Map.of());
        if (fetched.isEmpty() || fetched.get().status() != 200) {
            return false;
        }
        Publication publication = new Publication(store);
        publication.link(path, publication.storeBlob(fetched.get().body()));
        handle(exchange, store);
        return true;
    }

    private void listing(String path, ArtifactStore store, FormatExchange exchange) throws IOException {
        List<String> children = store.list("publish" + path.substring(0, path.length() - 1));
        if (children.isEmpty()) {
            exchange.respond(404);
            return;
        }
        StringBuilder html = new StringBuilder("<!DOCTYPE html>\n<html><body>\n");
        for (String child : children) {
            html.append("<a href=\"").append(child).append("\">").append(child).append("</a><br/>\n");
        }
        html.append("</body></html>\n");
        exchange.setResponseHeader("Content-Type", "text/html");
        exchange.respond(200, html.toString().getBytes(StandardCharsets.UTF_8));
    }
}
