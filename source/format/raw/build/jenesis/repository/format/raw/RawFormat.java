package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
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
public final class RawFormat implements RepositoryFormat, ProxyFormat, RepositoryImporter {

    // Reused across listings rather than rebuilt per request: newInstance() runs the full JAXP provider lookup, and the
    // factory is safe to share for creating writers once configured.
    private static final XMLOutputFactory XML_OUTPUT = XMLOutputFactory.newInstance();

    /** How many immediate child names a listing pages from the store at a time - the directory is enumerated through
     *  repeated bounded pages rather than one whole-directory {@code list()} snapshot, so a raw directory with an
     *  enormous fan-out is never materialised twice (the raw child set and the screened subset) in heap at once. */
    private static final int LISTING_PAGE = 1_000;

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link RawImporter} - the format IS
     *  the discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final RawImporter importer = new RawImporter();

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
                // Layout-only (EPIC 26): screening rides the ingress edge, which screens the body to ACCEPT and
                // restreams the stored blob into this format. Store content-addressed (streamed, never buffered) and
                // link the path, then respond 201 - verdicts are the edge's business, not the format's.
                String hash = publication.storeBlob(exchange.requestStream());
                publication.link(path, hash);
                exchange.respond(201);
            }
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
            // Layout-only (EPIC 26): screening rides the ingress edge (under enterprise the proxy ingress is already
            // screened by ProxyScreen/harden), so this lays the fetched body out - store it content-addressed
            // (streamed, never buffered) and link the path - and the handle() re-dispatch serves it.
            String hash = publication.storeBlob(download.body());
            publication.link(path, hash);
        }
        handle(exchange, store);
        return true;
    }

    private void listing(String path, ArtifactStore store, FormatExchange exchange) throws IOException {
        String prefix = "publish" + path.substring(0, path.length() - 1);
        // The directory listing must not disclose a leaf a GET/HEAD would not serve: located() applies the withheld
        // (quarantine/retraction) screens, so a withheld artifact 404s on GET but its pointer name still lives under
        // publish/, and writing every child verbatim leaked the existence - and the name - of a withheld artifact.
        // Screen each leaf the same way the item routes do. A child that is itself a directory (it has its own
        // children under publish/) is a sub-listing, not a servable leaf, so it is kept unconditionally; a leaf is
        // kept only when located() resolves it (published, blob present, not withheld).
        //
        // Page the immediate children rather than materialising the whole directory as one list() snapshot, and probe
        // folder-ness with a bounded one-element page instead of listing (and discarding) each child's entire subtree -
        // the old list(child).isEmpty() was a full subtree scan per child, quadratic across a large directory. Only the
        // screened-visible names are retained (they are rendered anyway); the raw child set is never held whole.
        Publication publication = new Publication(store);
        List<String> visible = new ArrayList<>();
        List<String> page = new ArrayList<>();
        String startAfter = "";
        do {
            page.clear();
            store.page(prefix, startAfter, LISTING_PAGE, page::add);
            for (String child : page) {
                if (hasChild(store, prefix + "/" + child) || publication.located(path + child).isPresent()) {
                    visible.add(child);
                }
            }
            startAfter = page.isEmpty() ? null : page.get(page.size() - 1);
        } while (startAfter != null && page.size() == LISTING_PAGE);
        if (visible.isEmpty()) {
            exchange.respond(404);   // no children at all, or every child screened away - both 404, as before
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter writer = XML_OUTPUT.createXMLStreamWriter(out, "UTF-8");
            writer.writeDTD("<!DOCTYPE html>");
            writer.writeStartElement("html");
            writer.writeStartElement("body");
            for (String child : visible) {
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

    /** Whether a prefix has at least one immediate child, tested with a bounded one-element page rather than listing
     *  (and discarding) the child's entire subtree just to check emptiness - so classifying a child as a directory is a
     *  single seek, not O(its own child count) round-trips. */
    private static boolean hasChild(ArtifactStore store, String prefix) {
        boolean[] any = {false};
        store.page(prefix, "", 1, _ -> any[0] = true);
        return any[0];
    }

    // --- RepositoryImporter capability (WSPI.2 (c)): delegated to RawImporter. ---

    @Override
    public boolean imports(String sourceFormat) {
        return importer.imports(sourceFormat);
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        return importer.importTarget(sourcePath);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        importer.importArtifact(path, content, store);
    }
}
