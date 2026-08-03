package build.jenesis.repository.format.oci;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.PrivateHosts;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OCI / Docker registry format (the {@code /v2/} Distribution API), so {@code docker push} and
 * {@code docker pull} work against the same store. It is an unusually clean fit: an OCI blob is addressed by its
 * {@code sha256:<hex>} digest, which is exactly the content-addressed {@code blobs/<hex>} the repository already
 * uses, so layers, configs and manifests dedupe against - and share storage with - everything else. A push
 * uploads blobs (monolithic, or a session of chunks) then a manifest, both stored by digest; a tag is a small
 * pointer ({@code oci/<name>/tags/<tag>} to a digest); a manifest's media type is kept in a sidecar so a pull
 * returns it verbatim. Stateless: the dispatcher passes the tenant-and-repository-scoped store on each call.
 */
public final class OciFormat implements RepositoryFormat, ProxyFormat, RepositoryImporter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link OciImporter} - the format IS
     *  the discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final OciImporter importer = new OciImporter();

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private static final String MANIFEST_ACCEPT = String.join(", ", OCI_MANIFEST,
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.docker.distribution.manifest.list.v2+json");

    /** The chunks of an in-flight chunked upload, staged by session id before they are finalized into a blob. */
    /** The manifest PUT body is buffered whole (it is metadata, never a layer blob) to hand the same bytes to the
     *  screen, so it must be bounded: a hostile authenticated pusher must not be able to OOM the shared JVM with a
     *  multi-GB "manifest". 4 MiB is far above any real image manifest / index (a few KiB) yet caps the buffer.
     *  Package-private so the migration-import path ({@link OciImporter}) bounds an imported manifest identically. */
    static final int MAX_MANIFEST = 4 * 1024 * 1024;

    private static final String UPLOADS = "oci/uploads/";

    /** One start-time marker per open upload session, in its own namespace - kept out of the session's numbered
     *  chunks (so it never disturbs chunk indexing) and out of the quota-metered {@link #UPLOADS} staging (so the
     *  tiny marker is never itself counted). The reaper ages a never-finalized session out by this marker. */
    private static final String SESSIONS = "oci/upload-sessions/";

    /** How long an un-finalized chunked-upload session is kept before {@link #reap} drops it. A {@code docker push}
     *  that opens a session and streams chunks but never finalizes it (a crashed or hostile client) is stored bytes
     *  that count against the quota, so it is swept once this stale rather than growing the store without bound. */
    private static final Duration UPLOAD_SESSION_TTL = Duration.ofHours(24);

    private final Clock clock;
    private final Duration uploadTtl;

    public OciFormat() {
        this(Clock.systemUTC(), UPLOAD_SESSION_TTL);
    }

    /** The {@link Clock} and TTL seam lets a test open a session, advance time past the TTL and assert the reaper
     *  drops it without sleeping - the injectable-clock-over-a-wall-clock-default idiom the negative cache and the
     *  mark-sweep collector expose as a public constructor for the same reason. */
    public OciFormat(Clock clock, Duration uploadTtl) {
        this.clock = clock;
        this.uploadTtl = uploadTtl;
    }

    @Override
    public String name() {
        return "oci";
    }

    @Override
    public boolean handles(String path) {
        return path.equals("/v2") || path.equals("/v2/") || path.startsWith("/v2/");
    }

    /**
     * The OCI protocol pushes one image across many requests - a session of blob uploads (a {@code POST} then chunked
     * {@code PATCH}es and a finalising {@code PUT}), then a manifest {@code PUT} that references them by digest - so no
     * single request carries the whole artifact for an ingress edge to screen as one body. This format therefore owns
     * its own screening choreography (a manifest-time choke point) and opts out of the edge screen, which would
     * otherwise store and gate each transport-level fragment as if it were a standalone publish.
     */
    @Override
    public boolean screened() {
        return false;
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        if (path.equals("/v2") || path.equals("/v2/")) {
            exchange.setResponseHeader("Docker-Distribution-Api-Version", "registry/2.0");
            exchange.respond(200);
            return;
        }
        String rest = path.substring("/v2/".length());
        if (rest.equals("_catalog")) {
            catalog(store, exchange);
            return;
        }
        if (rest.endsWith("/tags/list")) {
            tags(rest.substring(0, rest.length() - "/tags/list".length()), store, exchange);
            return;
        }
        int uploads = rest.indexOf("/blobs/uploads");
        if (uploads >= 0) {
            upload(rest.substring(0, uploads), rest.substring(uploads + "/blobs/uploads".length()), store, exchange);
            return;
        }
        int blobs = rest.indexOf("/blobs/");
        if (blobs >= 0) {
            blob(rest.substring(blobs + "/blobs/".length()), store, exchange);
            return;
        }
        int manifests = rest.indexOf("/manifests/");
        if (manifests >= 0) {
            manifest(rest.substring(0, manifests), rest.substring(manifests + "/manifests/".length()), store, exchange);
            return;
        }
        exchange.respond(404);
    }

    private void blob(String digest, ArtifactStore store, FormatExchange exchange) throws IOException {
        String hex = hex(digest);
        if (!isDigestHex(hex)) {
            // A blob is addressed by its sha256 digest; a reference that is not 64 lowercase hex chars cannot name a
            // blob, and refusing it here stops a '..'-laced digest aiming the blobs/<hex> key at another key space.
            exchange.respond(404);
            return;
        }
        String key = "blobs/" + hex;
        // The withheld/<hash> marker is the blobs-namespace twin of the publish/ namespace's quarantine screen (a
        // store-layout convention, like gc/condemned/<hash>): OCI serves by digest straight from blobs/, which no
        // publish/ pointer hold ever reached, so a compliance hold on these bytes retracts serving here through the
        // marker instead. Absent marker, zero-cost beyond one existence probe.
        if (!store.exists(key) || store.exists("withheld/" + hex)) {
            exchange.respond(404);
            return;
        }
        long size = store.size(key);
        exchange.setResponseHeader("Docker-Content-Digest", digest);
        exchange.setResponseHeader("Content-Type", "application/octet-stream");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200);
            return;
        }
        try (OutputStream out = exchange.respond(200, size)) {
            store.read(key, out);
        }
    }

    private void upload(String name, String session, ArtifactStore store, FormatExchange exchange) throws IOException {
        if (!isImageName(name)) {
            exchange.respond(404);                              // a traversal-laced image name opens no upload session
            return;
        }
        String method = exchange.method();
        if (method.equals("POST")) {
            // Fresh-upload sweep (the negative cache's "a fresh miss first sweeps expired entries" idiom): drop every
            // session abandoned past the TTL before opening a new one, so an un-finalized session's staged chunks are
            // reclaimed - and released from the quota counter - without needing a scheduler.
            reap(store);
            String digest = exchange.queryParameter("digest");
            if (digest != null) {
                store(digest, exchange.requestStream(), store, name, exchange);
                return;
            }
            String id = UUID.randomUUID().toString();
            // Record when the session opened so the reaper can age it out if it is streamed into but never finalized.
            writeSession(store, id, clock.millis(), 0L, 0L);
            exchange.setResponseHeader("Location", "/v2/" + name + "/blobs/uploads/" + id);
            exchange.setResponseHeader("Docker-Upload-UUID", id);
            exchange.setResponseHeader("Range", "0-0");
            exchange.respond(202);
            return;
        }
        String id = session.startsWith("/") ? session.substring(1) : session;
        if (!isImageName(id)) {
            exchange.respond(404);                              // a client-supplied, traversal-laced session id names
            return;                                             // no upload; the id must not aim an oci/uploads/<id> key
        }
        if (method.equals("PATCH")) {
            long uploaded = append(store, id, exchange.requestStream());
            exchange.setResponseHeader("Location", "/v2/" + name + "/blobs/uploads/" + id);
            exchange.setResponseHeader("Docker-Upload-UUID", id);
            exchange.setResponseHeader("Range", "0-" + (uploaded - 1));
            exchange.respond(202);
            return;
        }
        if (method.equals("PUT")) {
            String digest = exchange.queryParameter("digest");
            append(store, id, exchange.requestStream());
            try (InputStream combined = chunks(store, id)) {
                store(digest, combined, store, name, exchange);
            } finally {
                cleanup(store, id);
            }
            return;
        }
        exchange.respond(404);
    }

    /** Stream one received chunk straight to its own object under the upload session, indexed by its arrival order, so
     *  a chunked docker push never accumulates the growing layer in memory. The session marker carries the running
     *  chunk count and received-byte total, advanced by one write here, so neither the next chunk index nor the
     *  received-bytes total needs a full re-list / re-sum of the staged chunks per {@code PATCH} - an N-chunk push
     *  stays O(N), not O(N^2), store round-trips (the old per-PATCH re-sum cost ~N^2/2 {@code HEAD}s on an object
     *  store). Returns the running byte total for the {@code Range} header. */
    private long append(ArtifactStore store, String id, InputStream chunk) throws IOException {
        long[] session = session(store, id);
        long timestamp = session[0] == 0L ? clock.millis() : session[0];   // a stray chunk with no POST starts the clock
        long index = session[1];
        store.write("oci/uploads/" + id + "/" + index, chunk);
        long size = Math.max(store.size("oci/uploads/" + id + "/" + index), 0L);
        long bytes = session[2] + size;
        writeSession(store, id, timestamp, index + 1, bytes);
        return bytes;
    }

    /** The session marker as {@code [openMillis, chunkCount, byteTotal]}, all-zero when no marker is present. The
     *  first line is the open timestamp {@link #reap} ages the session out on; the chunk count and byte total follow,
     *  advanced per chunk by {@link #append} so the next index and {@code Range} total are read, never re-scanned. */
    private static long[] session(ArtifactStore store, String id) throws IOException {
        Optional<ArtifactStore.Versioned> marker = store.readVersioned(SESSIONS + id);
        if (marker.isEmpty()) {
            return new long[] {0L, 0L, 0L};
        }
        String[] lines = new String(marker.get().content(), StandardCharsets.UTF_8).trim().split("\n", -1);
        return new long[] {sessionField(lines, 0), sessionField(lines, 1), sessionField(lines, 2)};
    }

    private static long sessionField(String[] lines, int index) {
        if (index >= lines.length) {
            return 0L;
        }
        try {
            return Long.parseLong(lines[index].trim());
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    /** Write the session marker: the open timestamp on the first line (what {@link #reap} reads), then the running
     *  chunk count and received-byte total {@link #append} advances per chunk. */
    private static void writeSession(ArtifactStore store, String id, long openMillis, long count, long bytes)
            throws IOException {
        store.write(SESSIONS + id, new ByteArrayInputStream(
                (openMillis + "\n" + count + "\n" + bytes).getBytes(StandardCharsets.UTF_8)));
    }

    /** The session's chunks concatenated in arrival order as one stream, each opened only once the previous is
     *  drained, so finalizing a chunked upload streams the whole layer through {@link ArtifactStore#writeBlob}
     *  without ever holding it in memory. */
    private static InputStream chunks(ArtifactStore store, String id) {
        List<String> indices = new ArrayList<>(store.list("oci/uploads/" + id));
        indices.sort(Comparator.comparingInt(Integer::parseInt));
        Iterator<String> iterator = indices.iterator();
        return new SequenceInputStream(new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            @Override
            public InputStream nextElement() {
                try {
                    return store.open("oci/uploads/" + id + "/" + iterator.next());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    /** Drop every chunk object of a finalized (or abandoned) upload session, then its start marker. The marker is
     *  deleted last, so a crash mid-cleanup leaves it behind for the reaper to retry against rather than orphaning
     *  the chunks - the same converge-through-the-store, fail-toward-a-retry ordering the delete path elsewhere uses. */
    private static void cleanup(ArtifactStore store, String id) throws IOException {
        for (String index : store.list("oci/uploads/" + id)) {
            store.delete("oci/uploads/" + id + "/" + index);
        }
        store.delete(SESSIONS + id);
    }

    /** Drop every upload session whose start marker is older than the TTL - a chunked push that opened a session
     *  (and possibly streamed chunks into the quota-metered {@link #UPLOADS} staging) but never finalized it. The
     *  reclaimed chunk bytes converge back out of the quota counter through {@link #cleanup}'s metered deletes.
     *  Returns the number of sessions reaped. */
    public int reap(ArtifactStore store) throws IOException {
        Instant cutoff = clock.instant().minus(uploadTtl);
        int reaped = 0;
        for (String id : store.list("oci/upload-sessions")) {
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(SESSIONS + id);
            if (marker.isEmpty()) {
                continue;
            }
            Instant startedAt;
            try {
                // The open timestamp is the marker's first line; the chunk count and byte total follow it.
                String first = new String(marker.get().content(), StandardCharsets.UTF_8).trim().split("\n", 2)[0];
                startedAt = Instant.ofEpochMilli(Long.parseLong(first.trim()));
            } catch (NumberFormatException malformed) {
                continue; // a marker we cannot read as a timestamp is left for an operator, never blindly reaped
            }
            if (startedAt.isBefore(cutoff)) {
                cleanup(store, id);
                reaped++;
            }
        }
        return reaped;
    }

    private void store(String digest, InputStream content, ArtifactStore store, String name, FormatExchange exchange)
            throws IOException {
        // writeBlob digests the stream as it stores it under blobs/<hex> (deduping against an identical blob), so the
        // pushed layer goes from the network to storage without being buffered whole to be hashed first.
        String hex = store.writeBlob(content);
        if (digest != null && !hex.equals(hex(digest))) {
            exchange.respond(400);
            return;
        }
        exchange.setResponseHeader("Location", "/v2/" + name + "/blobs/sha256:" + hex);
        exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
        exchange.respond(201);
    }

    private void manifest(String name, String reference, ArtifactStore store, FormatExchange exchange)
            throws IOException {
        if (!isImageName(name)) {
            exchange.respond(404);                              // a traversal-laced image name names no manifest
            return;
        }
        if (exchange.method().equals("PUT")) {
            if (!reference.startsWith("sha256:") && !isTag(reference)) {
                // A manifest is pushed either by digest (sha256:...) or by tag; a reference that is neither a digest
                // nor a well-formed tag would land as an oci/<name>/tags/<ref> store key, so a '/'- or '..'-laced
                // reference could aim the write at a neighbouring key space - refuse it before storing anything, the
                // tag-side counterpart of the isDigestHex guard on the blob path.
                exchange.respond(400);
                return;
            }
            // Route the manifest through the OCI choke point (EPIC 26): OciManifests.ingest screens it against its
            // neutral oci coordinate and maps the verdict onto the native withheld/<hex> marker. Buffer it whole (it is
            // metadata, never a layer blob) to hand the same bytes to the screen - but BOUNDED: OCI opts out of the
            // ingress edge screen, so a manifest PUT reaches here with the raw request stream; an uncapped readAllBytes
            // would let an authenticated pusher OOM the shared JVM (a cross-tenant DoS). Read one byte past the cap and
            // refuse a body that overflows it, the manifest-side counterpart of the NuGet .nuspec readNBytes cap.
            byte[] body = exchange.requestStream().readNBytes(MAX_MANIFEST + 1);
            if (body.length > MAX_MANIFEST) {
                exchange.setResponseHeader("Content-Type", "application/json");
                exchange.respond(413, ("{\"errors\":[{\"code\":\"MANIFEST_INVALID\",\"message\":"
                        + "\"manifest exceeds the " + MAX_MANIFEST + "-byte limit\"}]}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            OciManifests.Ingested ingested = OciManifests.ingest(
                    name, reference, body, exchange.requestHeader("Content-Type"), store);
            String hex = ingested.hex();
            // A push BY DIGEST must actually hash to that digest - the manifest-side counterpart of the blob store()
            // content-address check. Without this a client could PUT /manifests/sha256:<X> with a body that hashes to
            // Y, and the registry would accept it and answer Docker-Content-Digest: sha256:Y, silently disagreeing
            // with the reference the client (and any content-addressed puller) used. Refuse the mismatch as invalid.
            if (reference.startsWith("sha256:") && !reference.substring("sha256:".length()).equalsIgnoreCase(hex)) {
                exchange.setResponseHeader("Content-Type", "application/json");
                exchange.respond(400, ("{\"errors\":[{\"code\":\"MANIFEST_INVALID\",\"message\":"
                        + "\"the manifest body does not hash to the referenced digest\"}]}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            switch (ingested.disposition()) {
                case ACCEPT -> {
                    exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
                    exchange.setResponseHeader("Location", "/v2/" + name + "/manifests/sha256:" + hex);
                    exchange.respond(201);
                }
                case QUARANTINE -> {
                    // Held for review: accepted onto the registry as a 202, but withheld from serving until released.
                    exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
                    exchange.respond(202);
                }
                case REJECT -> {
                    // Denied outright with the Distribution error envelope, so `docker push` surfaces the refusal.
                    exchange.setResponseHeader("Content-Type", "application/json");
                    exchange.respond(403, ("{\"errors\":[{\"code\":\"DENIED\",\"message\":"
                            + "\"manifest withheld by the compliance screen\"}]}").getBytes(StandardCharsets.UTF_8));
                }
            }
            return;
        }
        String hex;
        if (reference.startsWith("sha256:")) {
            hex = reference.substring("sha256:".length());
        } else {
            if (!isTag(reference)) {
                exchange.respond(404);                          // a '/'- or '..'-laced tag names no pointer (symmetric
                return;                                         // with the PUT path's guard - never a raw store key)
            }
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned("oci/" + name + "/tags/" + reference);
            if (pointer.isEmpty()) {
                exchange.respond(404);
                return;
            }
            hex = hex(new String(pointer.get().content(), StandardCharsets.UTF_8).trim());
        }
        if (!isDigestHex(hex)) {
            exchange.respond(404);
            return;
        }
        String key = "blobs/" + hex;
        // A withheld manifest 404s exactly as a withheld blob does (the withheld/<hash> convention above), so a held
        // image cannot be pulled by digest or tag while its layers 404.
        if (!store.exists(key) || store.exists("withheld/" + hex)) {
            exchange.respond(404);
            return;
        }
        String type = store.readVersioned("oci/types/" + hex)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim())
                .orElse(OCI_MANIFEST);
        long size = store.size(key);
        exchange.setResponseHeader("Content-Type", type);
        exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200);
            return;
        }
        try (OutputStream out = exchange.respond(200, size)) {
            store.read(key, out);
        }
    }

    /** The number of tag / image-name pointers paged from the store per seek-resume batch: large enough that a full
     *  page of results is usually one round-trip, bounded so a single request never materialises an arbitrarily large
     *  child set (the flat {@code oci/<name>/tags} set, or a directory level of the {@code oci/} image tree) as one
     *  {@link ArtifactStore#list} + sort. Over-fetching past withheld pointers pages on across batches as needed. */
    private static final int PAGE_BATCH = 256;

    /**
     * {@code GET /v2/<name>/tags/list} honouring the Distribution API's optional {@code n} (max results) and
     * {@code last} (resume-after) paging: the tag pointer set is paged through the store's
     * {@link ArtifactStore#page seek-resume primitive} (immediate children, lexicographic order) rather than listed and
     * sorted whole, so a request reads a bounded window of tags, not the entire set into heap. A withheld tag is
     * screened out exactly as its manifest 404s on a pull - the tags/list must not disclose a tag whose manifest is
     * held (AUDIT §5/§8, its existence included) - and the walk over-fetches past withheld tags so a full page of
     * <em>servable</em> tags is still returned when some are screened. When a further page remains a
     * {@code Link; rel="next"} carries the next {@code n}/{@code last}, exactly as {@link #catalog} does for
     * {@code _catalog}.
     */
    private void tags(String name, ArtifactStore store, FormatExchange exchange) throws IOException {
        if (!isImageName(name)) {
            exchange.respond(404);                              // a traversal-laced image name lists no tags
            return;
        }
        Integer limit = pageSize(exchange);
        if (limit == null) {
            return;                                             // a non-numeric or non-positive n is a 400, already sent
        }
        String last = exchange.queryParameter("last");
        String base = "oci/" + name + "/tags";
        ServableNames names = new ServableNames(store);
        List<String> tags = new ArrayList<>();
        boolean more = false;
        String cursor = last == null ? "" : last;
        paging:
        while (true) {
            List<String> batch = new ArrayList<>();
            store.page(base, cursor, PAGE_BATCH, batch::add);
            if (batch.isEmpty()) {
                break;                                          // no further children past the cursor
            }
            cursor = batch.getLast();
            for (String tag : batch) {
                if (tagWithheld(names, store, name, tag)) {
                    continue;                                   // over-fetch past a withheld tag, never disclosing it
                }
                if (tags.size() == limit) {
                    more = true;                                // a servable tag beyond the page proves a next page
                    break paging;
                }
                tags.add(tag);
            }
            if (batch.size() < PAGE_BATCH) {
                break;                                          // the store had no full batch left - the set is drained
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("tags", tags);
        if (more) {
            exchange.setResponseHeader("Link", "</v2/" + name + "/tags/list?n=" + limit + "&last="
                    + URLEncoder.encode(tags.getLast(), StandardCharsets.UTF_8) + ">; rel=\"next\"");
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    /** Parse the Distribution {@code n} page-size query parameter shared by {@code tags/list} and {@code _catalog}:
     *  absent means unbounded ({@link Integer#MAX_VALUE}); a non-numeric or non-positive {@code n} is refused
     *  {@code 400} (a zero or negative page size would otherwise read {@code getLast()} off an empty page or index a
     *  negative range - each an unhandled 500). Returns {@code null} once it has already responded {@code 400}. */
    private static Integer pageSize(FormatExchange exchange) throws IOException {
        String limit = exchange.queryParameter("n");
        int page;
        try {
            page = limit == null ? Integer.MAX_VALUE : Integer.parseInt(limit);
        } catch (NumberFormatException invalid) {
            exchange.respond(400);
            return null;
        }
        if (page <= 0) {
            exchange.respond(400);
            return null;
        }
        return page;
    }

    /** Whether the manifest {@code tag} of {@code name} points at is currently withheld - the tag resolved through its
     *  {@code oci/<name>/tags/<tag>} pointer to a digest, then screened by the same {@code withheld/<hash>} marker the
     *  blob and manifest serve paths honour (the one seam a held image 404s through). A tag whose pointer is missing or
     *  does not resolve to a real digest is not treated as withheld here - it is not a held artifact, and the serve
     *  path's own guards answer it - so only a genuinely held tag is dropped from the catalog and the tags/list.
     *
     *  <p>The tag pointer stores the digest in its {@code sha256:<hex>} display form (see {@link #linkTag}), so the
     *  marker probe is routed through {@link ServableNames#withheldHash} on the bare {@code hex} the {@code withheld/}
     *  marker is keyed by - not {@link ServableNames#disclosableKey}, which would probe {@code withheld/} with the
     *  {@code sha256:}-prefixed pointer content and so never match the bare-hex marker. This is the same seam face the
     *  blob and manifest serve paths honour, kept behaviour-identical to the former inline
     *  {@code store.exists("withheld/" + hex)}. */
    private static boolean tagWithheld(ServableNames names, ArtifactStore store, String name, String tag)
            throws IOException {
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned("oci/" + name + "/tags/" + tag);
        if (pointer.isEmpty()) {
            return false;
        }
        String hex = hex(new String(pointer.get().content(), StandardCharsets.UTF_8).trim());
        return isDigestHex(hex) && names.withheldHash(hex);
    }

    /**
     * The Distribution catalog ({@code GET /v2/_catalog}): every image name that carries at least one servable
     * (non-withheld) tag, in lexicographic order, honouring the API's optional {@code n} (max results) / {@code last}
     * (resume-after) paging with a {@code Link} to the next page - the index through which a registry (and a jenesis
     * repository serving this format) is enumerable, so migration off this repository works over the format's own
     * protocol.
     *
     * <p><b>Bounded, not a whole-plane scan.</b> A page is a bounded seek through the store's
     * {@link ArtifactStore#page seek-resume primitive} (see {@link #catalogPage}), never a {@code list}-every-node +
     * {@code Collections.sort} of every image name with a per-image tag re-list - which bounded only the JSON array,
     * not the work or heap, making a catalog walk O(total<sup>2</sup>). Only the images on (and just past) the page pay
     * the surviving-tag screen, and only the O(depth) active traversal frontier is held in heap.
     *
     * <p><b>Interim, not a durable index.</b> The audit's ideal is a durable, generation-flipped, lexicographically
     * sorted image-name index rebuilt by a fleet-exclusive maintenance task, read as a pure seek. This edition has no
     * such generation-index precedent to mirror (no {@code StorageNamespace}, no generation-directory /
     * {@code built}-marker index, and no scheduler wiring a format into {@code RebuildPass}), so rather than ship a
     * half-built durable index this bounds the per-request work and heap of the live walk and emits the same
     * {@code Link} paging - the same shape {@code tags/list} now uses.
     */
    private void catalog(ArtifactStore store, FormatExchange exchange) throws IOException {
        Integer limit = pageSize(exchange);
        if (limit == null) {
            return;                                             // a non-numeric or non-positive n is a 400, already sent
        }
        String last = exchange.queryParameter("last");
        CatalogPage catalog = catalogPage(store, new ServableNames(store), last, limit);
        List<String> repositories = catalog.names();
        if (catalog.more()) {
            exchange.setResponseHeader("Link", "</v2/_catalog?n=" + limit + "&last="
                    + URLEncoder.encode(repositories.getLast(), StandardCharsets.UTF_8) + ">; rel=\"next\"");
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, JSON.writeValueAsString(Map.of("repositories", repositories))
                .getBytes(StandardCharsets.UTF_8));
    }

    /** One {@code _catalog} page: up to {@code limit} servable image names in lexicographic order, and whether a
     *  further servable name remains beyond it (the {@code Link; rel="next"} cue). */
    private record CatalogPage(List<String> names, boolean more) {
    }

    /**
     * Page up to {@code limit} servable image names, in lexicographic order, strictly after {@code last}, over the
     * {@code oci/} pointer tree - a bounded seek, never a whole-plane scan.
     *
     * <p>An image name is client-controlled and multi-segment, so the {@code oci/} tree is arbitrarily deep and broad.
     * This is an ordered k-way merge over that tree through the store's {@link ArtifactStore#page seek-resume
     * primitive}: a priority frontier of lazy directory cursors, each paging the immediate children of one tree level
     * on demand and peeking its next full name, so the globally smallest unseen name is always the frontier minimum.
     * Popping it emits it (screened by {@link #hasSurvivingTag}, over-fetching past withheld / childless-parent nodes)
     * and opens its own subtree. The frontier holds only the O(depth) cursors along the active descent - never the
     * whole name set - and the walk is iterative, so a deeply nested push cannot overflow the stack on a plain
     * {@code GET /v2/_catalog} (the {@code StackOverflowError} the old work-list walk was written to avoid).
     *
     * <p>Resume is a seek, not a rescan: {@link #seed} primes the frontier with cursors that only ever yield names
     * strictly greater than {@code last}, so a page never re-walks the names already served on earlier pages.
     */
    private CatalogPage catalogPage(ArtifactStore store, ServableNames names, String last, int limit)
            throws IOException {
        PriorityQueue<DirCursor> frontier = new PriorityQueue<>(Comparator.comparing(DirCursor::peekFull));
        seed(store, last, frontier);
        List<String> repositories = new ArrayList<>();
        boolean more = false;
        while (!frontier.isEmpty()) {
            DirCursor cursor = frontier.poll();
            String name = cursor.peekFull();                    // the frontier minimum - the smallest unseen name
            cursor.advance();
            if (cursor.hasChild()) {
                frontier.add(cursor);                           // its next sibling re-enters at its new position
            }
            DirCursor child = new DirCursor(store, name, "");   // descend - a deeper name may be servable
            if (child.hasChild()) {
                frontier.add(child);
            }
            // An image is catalogued only if it has a surviving (non-withheld) tag: a fully-held image - every tag's
            // manifest withheld - must not be disclosed while its bytes 404 (AUDIT §5/§8), just as a held tag is
            // dropped from tags/list. A childless parent name (no tags of its own) is screened out the same way.
            if (hasSurvivingTag(names, store, name)) {
                if (repositories.size() == limit) {
                    more = true;                                // a servable name beyond the page proves a next page
                    break;
                }
                repositories.add(name);
            }
        }
        return new CatalogPage(repositories, more);
    }

    /** Prime the merge frontier so every name it yields is strictly greater than {@code last} - the resume seek. With
     *  no {@code last}, one cursor over the {@code oci/} root. With a {@code last}, one cursor per level of {@code last}'s
     *  ancestor path paging that level's children after the path segment (the branches that sort after {@code last}),
     *  plus one over {@code last} itself (whose children all sort after it). Each path segment is used as a store
     *  <em>base</em> only after it is proven traversal-free (a {@code last} is a client query value, never validated as
     *  an image name); a segment used purely as a {@code startAfter} comparison is safe as-is. */
    private void seed(ArtifactStore store, String last, PriorityQueue<DirCursor> frontier) {
        if (last == null || last.isEmpty()) {
            add(frontier, new DirCursor(store, "", ""));
            return;
        }
        String[] segments = last.split("/", -1);
        StringBuilder prefix = new StringBuilder();
        boolean safe = true;
        for (int index = 0; index < segments.length && safe; index++) {
            // prefix is built only from already-safe segments, so it is a traversal-free base; segments[index] is used
            // here purely as a startAfter comparison string, so it needs no such check.
            add(frontier, new DirCursor(store, prefix.toString(), segments[index]));
            if (!safeSegment(segments[index])) {
                safe = false;                                   // a '..'/empty segment is no real image path - stop deeper
                break;
            }
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(segments[index]);
        }
        if (safe) {
            add(frontier, new DirCursor(store, prefix.toString(), ""));   // prefix == last; its children all sort after it
        }
    }

    /** A store-base segment is traversal-free when it is non-empty and neither {@code .} nor {@code ..} nor carries a
     *  backslash - the {@link #isImageName} per-segment test, applied to a {@code last} query value before it is used
     *  as part of an {@code oci/...} store base. */
    private static boolean safeSegment(String segment) {
        return !segment.isEmpty() && !segment.equals(".") && !segment.equals("..") && segment.indexOf('\\') < 0;
    }

    /** Add a cursor to the frontier only if it still has a child to peek - an exhausted cursor never enters. */
    private static void add(PriorityQueue<DirCursor> frontier, DirCursor cursor) {
        if (cursor.hasChild()) {
            frontier.add(cursor);
        }
    }

    /**
     * A lazy, resumable cursor over the immediate child directory names of one {@code oci/} tree level, paged through
     * {@link ArtifactStore#page} a bounded batch at a time and buffered, so a merge over a broad tree never lists a
     * whole directory level into heap. The format's own sidecar prefixes ({@code types}, {@code uploads},
     * {@code upload-sessions}) and the {@code tags} leaf are reserved by this layout and never image-name segments, so
     * they are skipped. Exposes the next child's full image name for the frontier ordering.
     */
    private static final class DirCursor {

        private final ArtifactStore store;
        private final String prefix;                            // the image-name path of this level, "" = oci/ root
        private final String base;                              // the store key prefix this level pages under
        private final Deque<String> buffer = new ArrayDeque<>();
        private String cursor;                                  // the store.page startAfter for the next batch
        private boolean exhausted;

        private DirCursor(ArtifactStore store, String prefix, String startAfter) {
            this.store = store;
            this.prefix = prefix;
            this.base = prefix.isEmpty() ? "oci" : "oci/" + prefix;
            this.cursor = startAfter;
            fill();
        }

        /** Refill the buffer from the next store page(s), skipping the reserved sidecar / {@code tags} names, until it
         *  holds a child or the level is drained - so {@link #peek} always sees the next real child if one remains. */
        private void fill() {
            while (buffer.isEmpty() && !exhausted) {
                List<String> batch = new ArrayList<>();
                store.page(base, cursor, PAGE_BATCH, batch::add);
                if (batch.isEmpty()) {
                    exhausted = true;
                    return;
                }
                cursor = batch.getLast();
                if (batch.size() < PAGE_BATCH) {
                    exhausted = true;
                }
                for (String child : batch) {
                    if (reserved(child)) {
                        continue;
                    }
                    buffer.add(child);
                }
            }
        }

        /** The reserved children that are never image-name segments: the {@code tags} leaf at any level, and the
         *  format's sidecar spaces at the {@code oci/} root ({@code prefix} empty). */
        private boolean reserved(String child) {
            return child.equals("tags") || prefix.isEmpty()
                    && (child.equals("types") || child.equals("uploads") || child.equals("upload-sessions"));
        }

        private boolean hasChild() {
            return !buffer.isEmpty();
        }

        /** The next child's full image name (the level prefix joined with the child segment) - the frontier key. */
        private String peekFull() {
            String child = buffer.peek();
            return prefix.isEmpty() ? child : prefix + "/" + child;
        }

        private void advance() {
            buffer.poll();
            fill();
        }
    }

    /** Whether {@code name} carries at least one non-withheld tag - the catalog inclusion test. Short-circuits at the
     *  first surviving tag, so an image with any servable tag costs one pointer resolve, and a wholly-withheld one is
     *  screened over the tag listing already in hand (bounded, §7 - never an unbounded re-list per image). */
    private static boolean hasSurvivingTag(ServableNames names, ArtifactStore store, String name) throws IOException {
        for (String tag : store.list("oci/" + name + "/tags")) {
            if (!tagWithheld(names, store, name, tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Proxy a {@code /v2/} manifest or blob miss to the upstream registry (Docker Hub by default). Blobs and
     * manifests are immutable by digest, so they are stored exactly as a push would and re-served locally; a
     * manifest by tag also records the tag pointer. Authentication follows the Distribution token flow: a
     * {@code 401} carries a {@code Bearer} challenge, the realm is exchanged for a token, and the fetch is retried.
     * The client {@code Accept} is forwarded so the upstream returns the right manifest media type (and image
     * index for multi-arch, whose per-architecture manifests are then proxied by digest in turn).
     */
    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/v2/")) {
            return false;
        }
        String rest = path.substring("/v2/".length());
        int blobs = rest.indexOf("/blobs/");
        if (blobs >= 0 && !rest.contains("/blobs/uploads")) {
            return proxyDigest(rest.substring(0, blobs), rest.substring(blobs + "/blobs/".length()), false,
                    null, exchange, store, upstream, fetcher);
        }
        int manifests = rest.indexOf("/manifests/");
        if (manifests >= 0) {
            return proxyDigest(rest.substring(0, manifests), rest.substring(manifests + "/manifests/".length()), true,
                    exchange.requestHeader("Accept"), exchange, store, upstream, fetcher);
        }
        return false;
    }

    private boolean proxyDigest(String name, String reference, boolean manifest, String accept,
                                FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        if (!isImageName(name)) {
            return false;                                       // a traversal-laced image name is no proxy target: the
                                                                // same in-format guard the direct manifest/tags/upload
        }                                                       // legs carry, so the proxy leg never leans on the firewall alone
        String root = upstream.toString();
        URI url = URI.create((root.endsWith("/") ? root : root + "/") + "v2/" + name
                + (manifest ? "/manifests/" : "/blobs/") + reference);
        if (manifest) {
            return proxyManifest(name, reference, accept, exchange, store, url, fetcher);
        }
        // Proxy-leg digest integrity for a blob, which is content-addressed by the reference itself. writeBlob streams
        // the download under a SHA-256 DigestInputStream and stores it at blobs/<its-own-hash> (never buffering the
        // layer whole, §1); the fetched bytes are then held to the requested digest. On a sha256 reference (the OCI
        // norm, and the only algorithm this content-addressed store keys on) a mismatch is REFUSED: the bytes land only
        // under their own true hash, so blobs/<requested> is never created - nothing is linked or served, the mismatched
        // object is left unreferenced for GC, and a re-pull re-hits upstream. A reference in another registered
        // algorithm the store cannot address (sha512:...) is left to the serve path, which 404s it since no
        // blobs/<sha256> key can answer it - today's behaviour, no fabricated cross-algorithm check.
        Optional<ProxyFormat.Download> fetched = download(url, accept, fetcher);
        if (fetched.isEmpty()) {
            return false;
        }
        try (ProxyFormat.Download download = fetched.get()) {
            if (download.status() != 200) {
                return false;
            }
            String hex = store.writeBlob(download.body());
            if (reference.startsWith("sha256:") && !hex.equals(hex(reference))) {
                return false;
            }
        }
        handle(exchange, store);
        return true;
    }

    /** A manifest is small and its media type comes from the response headers, so it is fetched buffered (not
     *  streamed): stored by digest, its type recorded in the sidecar, and, when referenced by a tag, the tag pointer
     *  updated. */
    private boolean proxyManifest(String name, String reference, String accept, FormatExchange exchange,
                                  ArtifactStore store, URI url, ProxyFormat.Fetcher fetcher) throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetch(url, accept, fetcher);
        if (fetched.isEmpty() || fetched.get().status() != 200) {
            return false;
        }
        byte[] body = fetched.get().body();
        String hex = sha256(body);
        // Proxy-leg digest integrity: hold the received manifest to every digest that is knowable here, refusing
        // (letting the local 404 stand) on any mismatch rather than caching corrupted-in-transit bytes under the digest
        // or tag a client will later trust - the manifest counterpart of the blob check above.
        //   - By-digest pull: the reference names the content digest, so the bytes must hash to it.
        if (reference.startsWith("sha256:") && !hex.equals(hex(reference))) {
            return false;
        }
        //   - By-tag pull: the upstream Docker-Content-Digest header carries the digest the registry addresses this
        //     manifest by (the mutable tag is only a pointer to it), so the received bytes are held to it when present.
        String contentDigest = fetched.get().header("Docker-Content-Digest");
        if (contentDigest != null && contentDigest.startsWith("sha256:") && !hex.equals(hex(contentDigest))) {
            return false;
        }
        //   - A mutable tag whose upstream response carries no (sha256) Docker-Content-Digest exposes no verifiable
        //     digest to check against, so this falls back to trusting the upstream response, as before - no fabricated
        //     check. The addressed digest is still recomputed and recorded by ingest() below, and a later by-digest
        //     re-pull of the same content is verified against it.
        if (!reference.startsWith("sha256:") && !isTag(reference)) {
            return false; // a non-tag reference must not become a tags/ store key - let the local 404 stand
        }
        // Screen a proxied manifest through the same OCI choke point a push takes (EPIC 26): a withheld upstream
        // manifest gets its withheld/<hex> marker set, so the handle() serve below 404s it by digest and by tag. There
        // is no separate proxy client response - the local serve is the response.
        OciManifests.ingest(name, reference, body, fetched.get().header("Content-Type"), store);
        handle(exchange, store);
        return true;
    }

    /** Fetch buffered through the Distribution bearer flow (for the small manifests a proxy must inspect): on a 401
     *  challenge, exchange the realm for a token and retry once. */
    private Optional<ProxyFormat.Fetched> fetch(URI url, String accept, ProxyFormat.Fetcher fetcher) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (accept != null) {
            headers.put("Accept", accept);
        }
        Optional<ProxyFormat.Fetched> first = fetcher.fetch(url, headers);
        if (first.isEmpty() || first.get().status() != 401) {
            return first;
        }
        String challenge = first.get().header("WWW-Authenticate");
        if (challenge == null || !challenge.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return first;
        }
        String token = token(challenge.substring("Bearer ".length()), fetcher, url.getHost());
        if (token == null) {
            return first;
        }
        headers.put("Authorization", "Bearer " + token);
        return fetcher.fetch(url, headers);
    }

    /** Stream a download through the Distribution bearer flow (for a large blob): try once, and on a 401 Bearer
     *  challenge exchange the realm for a token and retry streaming with it. Empty if the fetch fails or the challenge
     *  cannot be satisfied, so the caller lets the local 404 stand rather than serving a partial blob. */
    private Optional<ProxyFormat.Download> download(URI url, String accept, ProxyFormat.Fetcher fetcher)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (accept != null) {
            headers.put("Accept", accept);
        }
        Optional<ProxyFormat.Download> first = fetcher.download(url, headers);
        if (first.isEmpty() || first.get().status() != 401) {
            return first;
        }
        String token;
        try (ProxyFormat.Download unauthorized = first.get()) {
            String challenge = unauthorized.header("WWW-Authenticate");
            if (challenge == null || !challenge.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                return Optional.empty();
            }
            token = token(challenge.substring("Bearer ".length()), fetcher, url.getHost());
        }
        if (token == null) {
            return Optional.empty();
        }
        headers.put("Authorization", "Bearer " + token);
        return fetcher.download(url, headers);
    }

    /**
     * Walk an upstream registry through its own Distribution index: page the {@code /v2/_catalog} repository list,
     * page each image's {@code /v2/<name>/tags/list}, and expand each tagged manifest - an image index's
     * per-platform manifests first, then a manifest's config and layer blobs, then the manifest itself - so an
     * import stores every blob before the manifest and tag pointer that reference it. Blob and by-digest manifest
     * coordinates are deduplicated across the walk (tags share layers); the manifest fetch itself rides the same
     * bearer-challenge flow the proxy path uses. A registry that disables the catalog (Docker Hub does) answers
     * {@code 404} there, which surfaces as the initial index failure - enumeration honestly needs the catalog.
     */
    @Override
    public Stream<Coordinate> enumerate(ProxyFormat.Fetcher fetcher, URI upstream) throws IOException {
        String root = upstream.toString();
        URI base = URI.create(root.endsWith("/") ? root : root + "/");
        Iterator<String> repositories = paged(base, URI.create(base + "v2/_catalog"), "repositories", fetcher);
        Set<String> emitted = new HashSet<>();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(repositories, Spliterator.ORDERED), false)
                .flatMap(name -> {
                    try {
                        Iterator<String> tags = paged(base, URI.create(base + "v2/" + name + "/tags/list"), "tags", fetcher);
                        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(tags, Spliterator.ORDERED), false)
                                .map(tag -> Map.entry(name, tag));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .flatMap(tagged -> {
                    try {
                        List<Coordinate> coordinates = new ArrayList<>();
                        expand(base, tagged.getKey(), manifest(base, tagged.getKey(), tagged.getValue(), fetcher),
                                coordinates, emitted, fetcher);
                        coordinates.add(new Coordinate("v2/" + tagged.getKey() + "/manifests/" + tagged.getValue(),
                                URI.create(base + "v2/" + tagged.getKey() + "/manifests/" + tagged.getValue()),
                                Map.of("Accept", MANIFEST_ACCEPT)));
                        return coordinates.stream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * Add the coordinates one manifest transitively references, depth-first: an index's per-platform manifests (each
     * expanded then added by digest), a manifest's config and layers as blobs - each digest once per walk.
     *
     * <p>Walked with an explicit work-list, never recursion. A hostile or compromised upstream can serve a long chain
     * of nested image indices (index -&gt; index -&gt; ...); a recursive walk would drive one stack frame per level and
     * overflow the import worker's stack on that client-controlled depth - the same StackOverflow hazard the
     * {@code /v2/_catalog} walk was rewritten to avoid. The stack holds {@link Step}s that reproduce the recursion's
     * post-order exactly: every blob and every nested manifest is emitted before the manifest that references it, so an
     * import still stores a referent before its referrer.
     */
    private void expand(URI base, String name, byte[] manifest, List<Coordinate> coordinates, Set<String> emitted,
                        ProxyFormat.Fetcher fetcher) throws IOException {
        Deque<Step> pending = new ArrayDeque<>();
        pending.push(new Step(manifest, null, null));
        while (!pending.isEmpty()) {
            Step step = pending.pop();
            if (step.emit() != null) {
                coordinates.add(step.emit());               // the trailing manifest coordinate, after its subtree
                continue;
            }
            byte[] body = step.manifest() != null ? step.manifest() : manifest(base, name, step.digest(), fetcher);
            JsonNode node = JSON.readTree(new String(body, StandardCharsets.UTF_8));
            if (node.has("manifests")) {
                List<JsonNode> children = new ArrayList<>();
                node.path("manifests").forEach(children::add);
                // Push children in reverse so the first is walked first; per child its emit sits under its expand, so
                // the nested manifest's coordinate is added after everything that manifest references.
                for (int index = children.size() - 1; index >= 0; index--) {
                    String digest = children.get(index).path("digest").asString(null);
                    if (digest == null || !emitted.add(digest)) {
                        continue;
                    }
                    pending.push(new Step(null, null, new Coordinate("v2/" + name + "/manifests/" + digest,
                            URI.create(base + "v2/" + name + "/manifests/" + digest),
                            Map.of("Accept", MANIFEST_ACCEPT))));
                    pending.push(new Step(null, digest, null));
                }
                continue;
            }
            String config = node.path("config").path("digest").asString(null);
            if (config != null && emitted.add(config)) {
                coordinates.add(blob(base, name, config));
            }
            for (JsonNode layer : node.path("layers")) {
                String digest = layer.path("digest").asString(null);
                if (digest != null && emitted.add(digest)) {
                    coordinates.add(blob(base, name, digest));
                }
            }
            for (JsonNode layer : node.path("fsLayers")) {
                String digest = layer.path("blobSum").asString(null);
                if (digest != null && emitted.add(digest)) {
                    coordinates.add(blob(base, name, digest));
                }
            }
        }
    }

    /** One step of the iterative {@link #expand} walk: exactly one field is set - {@code manifest} for the already-
     *  fetched root body, {@code digest} for a nested manifest to fetch and expand when it is popped, or {@code emit}
     *  for a manifest coordinate to add once its subtree has been emitted. */
    private record Step(byte[] manifest, String digest, Coordinate emit) {
    }

    private static Coordinate blob(URI base, String name, String digest) {
        return new Coordinate("v2/" + name + "/blobs/" + digest, URI.create(base + "v2/" + name + "/blobs/" + digest));
    }

    /** One manifest, by tag or digest, negotiated with the manifest media types and fetched buffered (a manifest is
     *  small metadata) through the bearer-challenge flow. */
    private byte[] manifest(URI base, String name, String reference, ProxyFormat.Fetcher fetcher) throws IOException {
        URI url = URI.create(base + "v2/" + name + "/manifests/" + reference);
        Optional<ProxyFormat.Fetched> fetched = fetch(url, MANIFEST_ACCEPT, fetcher);
        if (fetched.isEmpty()) {
            throw new IOException("No response from " + url);
        }
        if (fetched.get().status() != 200) {
            throw new IOException("Manifest fetch failed (" + fetched.get().status() + ") for " + url);
        }
        return fetched.get().body();
    }

    /** Iterate one string-array field across the Distribution API's pages, following each page's
     *  {@code Link; rel="next"}. The first page is read eagerly (so an unreachable or catalog-less source fails
     *  the walk up front); later pages are read as the iteration reaches them. */
    private Iterator<String> paged(URI origin, URI first, String field, ProxyFormat.Fetcher fetcher) throws IOException {
        Page initial = page(origin, first, field, fetcher);
        return new Iterator<>() {
            private Page current = initial;
            private int index;

            @Override
            public boolean hasNext() {
                while (index == current.values().size() && current.next() != null) {
                    try {
                        current = page(origin, current.next(), field, fetcher);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    index = 0;
                }
                return index < current.values().size();
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.values().get(index++);
            }
        };
    }

    private record Page(List<String> values, URI next) {
    }

    /** Same scheme and authority (host:port) as the operator-configured enumeration root - the cross-origin test the
     *  PrivateHosts page guard uses, identical to {@code IndexSource.sameOrigin}. */
    private static boolean sameOrigin(URI origin, URI url) {
        return Objects.equals(origin.getScheme(), url.getScheme())
                && Objects.equals(origin.getRawAuthority(), url.getRawAuthority());
    }

    private Page page(URI origin, URI url, String field, ProxyFormat.Fetcher fetcher) throws IOException {
        // The next-page URL is resolved from the upstream's own Link header (below), so its host is upstream-controlled
        // and reaches fetch() as an INITIAL request - HttpFetcher's redirect-only SSRF screen never inspects it. Refuse
        // a CROSS-ORIGIN page aimed at a private/loopback/metadata host through the same PrivateHosts guard the redirect
        // chain and IndexSource.open use, so a malicious registry cannot steer catalog/tags pagination at 169.254.169.254
        // or an internal control plane. The first page is same-origin with the operator-configured root, so it passes.
        if (!sameOrigin(origin, url) && PrivateHosts.resolvesToPrivate(url.getHost())) {
            throw new IOException("Refusing a cross-origin catalog/tags page to a private/loopback host: " + url);
        }
        Optional<ProxyFormat.Fetched> fetched = fetch(url, "application/json", fetcher);
        if (fetched.isEmpty()) {
            throw new IOException("No response from " + url);
        }
        if (fetched.get().status() != 200) {
            throw new IOException("Index fetch failed (" + fetched.get().status() + ") for " + url);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : JSON.readTree(new String(fetched.get().body(), StandardCharsets.UTF_8)).path(field)) {
            String name = value.asString(null);
            if (name != null) {
                values.add(name);
            }
        }
        String link = fetched.get().header("Link");
        URI next = null;
        if (link != null && link.contains("rel=\"next\"")) {
            int open = link.indexOf('<');
            int close = link.indexOf('>');
            if (open >= 0 && close > open) {
                next = url.resolve(link.substring(open + 1, close));
            }
        }
        return new Page(List.copyOf(values), next);
    }

    private String token(String challenge, ProxyFormat.Fetcher fetcher, String upstreamHost) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : challenge.split(",")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                params.put(part.substring(0, equals).trim(),
                        part.substring(equals + 1).trim().replace("\"", ""));
            }
        }
        String realm = params.get("realm");
        if (realm == null) {
            return null;
        }
        StringBuilder url = new StringBuilder(realm);
        char separator = '?';
        for (String key : new String[]{"service", "scope"}) {
            String value = params.get(key);
            if (value != null) {
                url.append(separator).append(key).append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                separator = '&';
            }
        }
        URI realmUri;
        try {
            realmUri = URI.create(url.toString());
        } catch (IllegalArgumentException malformed) {
            return null;   // a realm that is not a valid URI cannot be exchanged for a token
        }
        // The bearer-token realm is chosen by the upstream's WWW-Authenticate challenge. It is trusted when it names
        // the SAME host the operator configured as the upstream - an internal mirror's own token endpoint, so a
        // private address there is expected. It is an SSRF when it names a DIFFERENT host that resolves to a private,
        // loopback, link-local or cloud-metadata address: the upstream is then steering the proxy into the proxy's OWN
        // internal network. Refuse only that cross-host-to-private hop (no token, so the caller lets the local 404
        // stand); the fetcher screens redirect hops on the same guard.
        String realmHost = realmUri.getHost();
        if (realmHost == null
                || (!realmHost.equalsIgnoreCase(upstreamHost) && PrivateHosts.resolvesToPrivate(realmHost))) {
            return null;
        }
        Optional<ProxyFormat.Fetched> response = fetcher.fetch(realmUri, Map.of());
        if (response.isEmpty() || response.get().status() != 200) {
            return null;
        }
        JsonNode token = JSON.readTree(new String(response.get().body(), StandardCharsets.UTF_8));
        String bearer = token.path("token").asString(null);
        return bearer != null ? bearer : token.path("access_token").asString(null);
    }

    private static String hex(String digest) {
        int colon = digest.indexOf(':');
        return colon < 0 ? digest : digest.substring(colon + 1);
    }

    /** Whether {@code hex} is exactly a 64-character lowercase sha256 hex string - the only shape that can name a
     *  {@code blobs/<hex>} object, so a reference that is not (a tag typo, a {@code ..}-laced digest) is refused
     *  before it becomes a store key rather than resolving to a neighbouring key space. */
    private static boolean isDigestHex(String hex) {
        if (hex.length() != 64) {
            return false;
        }
        for (int index = 0; index < 64; index++) {
            char character = hex.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    /** Whether a manifest reference is a well-formed OCI tag - the Distribution grammar {@code
     *  [a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}}. Refusing anything else before it becomes the {@code oci/<name>/tags/<ref>}
     *  store key keeps a {@code /}- or {@code ..}-laced reference from resolving to a neighbouring key space - the
     *  tag-side counterpart of {@link #isDigestHex} on the blob path (a bare {@code ..} is rejected as a leading dot,
     *  and any {@code /} is rejected outright, so no reference can traverse out of the tag namespace). */
    private static boolean isTag(String reference) {
        int length = reference.length();
        if (length == 0 || length > 128) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            char character = reference.charAt(index);
            boolean alphanumeric = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9');
            if (index == 0 ? !alphanumeric && character != '_'
                    : !alphanumeric && character != '_' && character != '.' && character != '-') {
                return false;
            }
        }
        return true;
    }

    /** Whether an image name is a traversal-free path of Distribution name segments - each non-empty and not {@code .}
     *  or {@code ..}, with no backslash - so a {@code ..}- or empty-segment-laced name can never aim an
     *  {@code oci/<name>/...} key at a neighbouring key space. The in-format counterpart of {@link #isDigestHex} and
     *  {@link #isTag} on the blob and tag paths: a multi-segment image name is the one request element that otherwise
     *  leans on the servlet firewall alone, so it is validated here before it becomes a store key (kept minimal - only
     *  the traversal-relevant segments - so every already-valid name still resolves). */
    private static boolean isImageName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0) {
                return false;
            }
        }
        return true;
    }

    /** Point a tag at a digest with the bounded compare-and-set retry every load-bearing pointer write uses (the
     *  {@code Publication.link} idiom): a concurrent re-tag of the same tag resolves last-writer-wins rather than one
     *  push silently dropping the other's update while still answering {@code 201}, and a write that cannot land after
     *  repeated conflicts surfaces as an {@link IOException} instead of a false success. */
    static void linkTag(ArtifactStore store, String key, String digest) throws IOException {
        byte[] value = digest.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 3; attempt++) {
            Object token = store.readVersioned(key).map(ArtifactStore.Versioned::token).orElse(null);
            if (store.writeVersioned(key, value, token)) {
                return;
            }
        }
        throw new IOException("could not link " + key + " after repeated version conflicts");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- RepositoryImporter capability (WSPI.2 (c)): delegated to OciImporter. importTarget returns empty - OCI owns
    //     its own manifest screening choke point, so the import walk lays each OCI asset out unscreened. ---

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
