package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Batch archive ingestion: a single publish request carrying an archive and the {@value #EXPLODE_HEADER} header is
 * walked sequentially with {@link ZipInputStream} (java.base, no dependency) and each entry is dispatched as its own
 * synthesized publish - method {@code PUT}, path = the request's base path joined with the entry path, body = the
 * entry's stream - through the normal {@link FormatDispatcher} (or any {@link EntryPublisher} a dispatcher wraps), so
 * it streams into the content-addressed store on the format's own publish path and the discovered publication-screen
 * chain applies <em>per entry</em>: a bad entry never taints its siblings. The contract is "each entry is a publish
 * request", so a raw-body-{@code PUT} format works natively and a protocol format works by carrying its protocol body
 * at its protocol path - no format learns anything about batching.
 *
 * <p>Nothing is materialized: neither the archive nor an entry is ever read whole into memory - the entry stream is
 * handed to the format, which streams it hash-on-write. The archive is opt-in (a deployment gate, {@code batch-upload},
 * off by default), entry-count capped (the zip-bomb axis that matters; entry size is harmless because everything
 * streams), nested archives are not re-exploded (an entry is just published, never re-walked), and entry paths are
 * traversal-guarded before any store touch. The response is a per-entry manifest ({@code path -> stored | quarantined
 * | rejected | unclaimed}) so a client sees exactly what each member became.
 */
public final class BatchIngestion {

    /** The request header naming the archive encoding to explode; only {@code zip} is understood. */
    public static final String EXPLODE_HEADER = "X-Jenesis-Explode";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What one exploded entry became once its synthesized publish ran the format and its screen chain. */
    public enum Outcome {
        /** The format accepted and linked the entry (the empty-chain default, or an {@code ACCEPT} verdict). */
        STORED,
        /** A screen quarantined the entry: stored for review, not served. */
        QUARANTINED,
        /** A screen rejected the entry: nothing linked, the orphan blob left for garbage collection. */
        REJECTED,
        /** No installed format claimed the entry's path - it is not part of any layout on this deployment. */
        UNCLAIMED
    }

    /** Publishes one exploded entry's stream at a synthesized path and reports what it became. */
    @FunctionalInterface
    public interface EntryPublisher {
        Outcome publish(String path, InputStream body) throws IOException;
    }

    private final BooleanSupplier enabled;
    private final IntSupplier maxEntries;

    /** @param enabled whether batch ingestion is switched on for this deployment (read live), and {@code maxEntries}
     *  the ceiling on how many members one archive may explode into (read live). */
    public BatchIngestion(BooleanSupplier enabled, IntSupplier maxEntries) {
        this.enabled = enabled;
        this.maxEntries = maxEntries;
    }

    /**
     * Whether this request is a batch explode this deployment will handle: the feature is enabled, the request is a
     * {@code PUT} or {@code POST}, and it carries the {@value #EXPLODE_HEADER} header. A dispatcher checks this before
     * its normal single-artifact dispatch; when it is false the header is inert and the request is a plain upload (so
     * an archive is stored verbatim as one artifact when the feature is off).
     */
    public boolean claims(FormatExchange exchange) {
        if (!enabled.getAsBoolean()) {
            return false;
        }
        String header = exchange.requestHeader(EXPLODE_HEADER);
        String method = exchange.method();
        return header != null && !header.isBlank() && ("PUT".equals(method) || "POST".equals(method));
    }

    /**
     * Explode by dispatching each entry through the normal {@link FormatDispatcher} - the format's own publish path,
     * so the discovered screen chain (a compliance gate, a quarantine audit, an inventory recorder) applies per entry
     * exactly as it does for a single upload. A {@code PUT} never proxies (see {@link PullThroughCache}), so an entry
     * whose format has an upstream configured still publishes locally.
     */
    public void explode(FormatExchange outer, ArtifactStore store, FormatDispatcher dispatcher) throws IOException {
        explode(outer, (path, body) -> dispatch(dispatcher, path, body, store));
    }

    /**
     * Explode the archive carried by {@code outer}, publishing each safe entry through {@code publisher} and writing a
     * per-entry JSON manifest as the response. The walk is sequential and streaming; it stops at the configured entry
     * cap (recording {@code "capped": true}) and refuses a path-traversing entry name before it reaches the store.
     */
    public void explode(FormatExchange outer, EntryPublisher publisher) throws IOException {
        String type = outer.requestHeader(EXPLODE_HEADER);
        if (type == null || !type.trim().equalsIgnoreCase("zip")) {
            outer.respond(415, ("unsupported explode archive encoding: " + type).getBytes(StandardCharsets.UTF_8));
            return;
        }
        String base = outer.path();
        int limit = Math.max(1, maxEntries.getAsInt());
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("explode", "zip");
        ArrayNode entries = manifest.putArray("entries");
        boolean capped = false;
        boolean malformed = false;
        int processed = 0;
        try (ZipInputStream zip = new ZipInputStream(outer.requestStream())) {
            while (true) {
                ZipEntry entry;
                try {
                    entry = zip.getNextEntry();
                } catch (ZipException _) {
                    malformed = true;
                    break;
                }
                if (entry == null) {
                    break;
                }
                if (entry.isDirectory()) {
                    continue; // directory markers carry no bytes to publish
                }
                if (processed >= limit) {
                    capped = true; // bounded: stop at the cap without reading the rest of the archive
                    break;
                }
                processed++;
                String name = entry.getName();
                String safe = safeEntryPath(name);
                ObjectNode record = entries.addObject();
                if (safe == null) {
                    record.put("path", name);
                    record.put("status", "rejected");
                    record.put("reason", "path-traversal");
                    continue;
                }
                String path = join(base, safe);
                record.put("path", path);
                record.put("status", label(publisher.publish(path, new Unclosable(zip))));
            }
        }
        manifest.put("capped", capped);
        if (malformed) {
            manifest.put("error", "malformed-archive");
        }
        outer.setResponseHeader("Content-Type", "application/json");
        outer.respond(malformed ? 400 : 200, JSON.writeValueAsBytes(manifest));
    }

    /** Dispatch one entry through the format loop and read its outcome off the status the format set. */
    private static Outcome dispatch(FormatDispatcher dispatcher, String path, InputStream body, ArtifactStore store)
            throws IOException {
        CapturingExchange exchange = new CapturingExchange(path, body);
        if (!dispatcher.dispatch(exchange, store)) {
            return Outcome.UNCLAIMED;
        }
        return switch (exchange.status()) {
            case 200, 201, 204 -> Outcome.STORED;
            case 202 -> Outcome.QUARANTINED;
            case 422 -> Outcome.REJECTED;
            default -> Outcome.UNCLAIMED;
        };
    }

    private static String label(Outcome outcome) {
        return switch (outcome) {
            case STORED -> "stored";
            case QUARANTINED -> "quarantined";
            case REJECTED -> "rejected";
            case UNCLAIMED -> "unclaimed";
        };
    }

    /** The base request path joined with a guarded (relative, no leading slash) entry path, exactly one {@code /}
     *  between them - the synthesized publish path the entry is dispatched to. */
    private static String join(String base, String entry) {
        String prefix = base == null || base.isEmpty() ? "/" : base;
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix + entry;
    }

    /** The entry name as a safe relative path, or {@code null} when it escapes the base (absolute, a {@code ..}
     *  segment, a NUL): Windows separators are folded to {@code /} first so a {@code ..\\..\\} traversal is caught by
     *  the same segment check. A safe name is relative and stays at or below the base prefix. */
    private static String safeEntryPath(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0) {
            return null;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return normalized;
    }

    /** Keeps the shared {@link ZipInputStream} open when a format closes the per-entry stream it was handed, so the
     *  next entry can still be read; the walk advances the zip itself. */
    private static final class Unclosable extends FilterInputStream {
        private Unclosable(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // the archive walk owns the ZipInputStream; a format closing its entry body must not close the archive
        }
    }

    /** A synthesized {@link FormatExchange} for one exploded entry: a {@code PUT} of the entry stream at its path, with
     *  the format's status captured and its (empty, for a publish) body discarded. It carries no request headers, so a
     *  format publishes the body plainly and no explode header can recurse. */
    private static final class CapturingExchange implements FormatExchange {

        private final String path;
        private final InputStream body;
        private int status;

        private CapturingExchange(String path, InputStream body) {
            this.path = path;
            this.body = body;
        }

        @Override
        public String method() {
            return "PUT";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return body;
        }

        @Override
        public void setResponseHeader(String name, String value) {
            // an entry's own response headers are irrelevant; the batch response is the manifest
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return OutputStream.nullOutputStream();
        }

        private int status() {
            return status;
        }
    }
}
