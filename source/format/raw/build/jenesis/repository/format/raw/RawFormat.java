package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * The generic (raw) format: a plain HTTP file store under {@code /raw/...}, for the artifacts that fit no package
 * ecosystem - installers, archives, datasets, signed binaries. A {@code PUT} stores the bytes content-addressed
 * through {@link Publication} (so a raw file that matches a jar, a tarball or an OCI layer dedupes to the one
 * {@code blobs/<sha256>}), a {@code GET} serves them, a {@code GET} on a trailing-slash path lists the directory,
 * and a {@code DELETE} removes the pointer. No metadata, no protocol - just the content-addressed store behind a
 * file API, so it is a thin plugin over the same primitives every other layout uses.
 */
public final class RawFormat implements RepositoryFormat, ProxyFormat {

    // Reused across listings rather than rebuilt per request: newInstance() runs the full JAXP provider lookup, and the
    // factory is safe to share for creating writers once configured.
    private static final XMLOutputFactory XML_OUTPUT = XMLOutputFactory.newInstance();

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
            case "PUT" -> exchange.respond(status(
                    publication.publish(ArtifactDescriptor.at("raw", path), exchange.requestStream()).disposition()));
            case "DELETE" -> {
                publication.unpublish(path);
                exchange.respond(204);
            }
            // HEAD must answer exactly what a GET would: located() applies the withheld (quarantine/retraction)
            // screens and confirms the content-addressed blob still exists, where blob() only reads the pointer -
            // so a withheld or GC-reclaimed path would otherwise HEAD 200 while GET 404s.
            case "HEAD" -> exchange.respond(publication.located(path).isPresent() ? 200 : 404);
            default -> {
                if (path.endsWith("/")) {
                    listing(path, store, exchange);
                    return;
                }
                Optional<String> key = publication.located(path);
                if (key.isEmpty()) {
                    exchange.respond(404);
                    return;
                }
                exchange.setResponseHeader("Content-Type", "application/octet-stream");
                try (OutputStream out = exchange.respond(200, store.size(key.get()))) {
                    store.read(key.get(), out);
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
        Optional<ProxyFormat.Download> fetched = fetcher.download(
                URI.create(root.endsWith("/") ? root + rest : root + "/" + rest), Map.of());
        if (fetched.isEmpty()) {
            return false;
        }
        Publication publication = new Publication(store);
        try (ProxyFormat.Download download = fetched.get()) {
            if (download.status() != 200) {
                return false;
            }
            publication.link(path, publication.storeBlob(download.body()));
        }
        handle(exchange, store);
        return true;
    }

    /** Map an upload disposition to the HTTP status a client sees: accepted is a created, quarantined is accepted (held
     *  for review), rejected is unprocessable. With the default empty interceptor chain this is always 201. */
    private static int status(PublishInterceptor.Disposition disposition) {
        return switch (disposition) {
            case ACCEPT -> 201;
            case QUARANTINE -> 202;
            case REJECT -> 422;
        };
    }

    private void listing(String path, ArtifactStore store, FormatExchange exchange) throws IOException {
        List<String> children = store.list("publish" + path.substring(0, path.length() - 1));
        if (children.isEmpty()) {
            exchange.respond(404);
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter writer = XML_OUTPUT.createXMLStreamWriter(out, "UTF-8");
            writer.writeDTD("<!DOCTYPE html>");
            writer.writeStartElement("html");
            writer.writeStartElement("body");
            for (String child : children) {
                writer.writeStartElement("a");
                writer.writeAttribute("href", child);
                writer.writeCharacters(child);
                writer.writeEndElement();
                writer.writeEmptyElement("br");
            }
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
        exchange.setResponseHeader("Content-Type", "text/html");
        exchange.respond(200, out.toByteArray());
    }
}
