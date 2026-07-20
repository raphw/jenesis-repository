package build.jenesis.repository.format.oci;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
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
public final class OciFormat implements RepositoryFormat, ProxyFormat {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private static final String MANIFEST_ACCEPT = String.join(", ", OCI_MANIFEST,
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.docker.distribution.manifest.list.v2+json");

    /** The chunks of an in-flight chunked upload, staged by session id before they are finalized into a blob. */
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
     *  mark-sweep collector use. */
    OciFormat(Clock clock, Duration uploadTtl) {
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
            store.write(SESSIONS + id, new ByteArrayInputStream(
                    Long.toString(clock.millis()).getBytes(StandardCharsets.UTF_8)));
            exchange.setResponseHeader("Location", "/v2/" + name + "/blobs/uploads/" + id);
            exchange.setResponseHeader("Docker-Upload-UUID", id);
            exchange.setResponseHeader("Range", "0-0");
            exchange.respond(202);
            return;
        }
        String id = session.startsWith("/") ? session.substring(1) : session;
        if (method.equals("PATCH")) {
            append(store, id, exchange.requestStream());
            exchange.setResponseHeader("Location", "/v2/" + name + "/blobs/uploads/" + id);
            exchange.setResponseHeader("Docker-Upload-UUID", id);
            exchange.setResponseHeader("Range", "0-" + (uploaded(store, id) - 1));
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

    /** Stream one received chunk straight to its own object under the upload session, indexed by its arrival order,
     *  so a chunked docker push never accumulates the growing layer in memory. */
    private static void append(ArtifactStore store, String id, InputStream chunk) throws IOException {
        store.write("oci/uploads/" + id + "/" + store.list("oci/uploads/" + id).size(), chunk);
    }

    /** The bytes received for this upload session so far - the sum of its chunk sizes - for the {@code Range} header. */
    private static long uploaded(ArtifactStore store, String id) throws IOException {
        long total = 0;
        for (String index : store.list("oci/uploads/" + id)) {
            total += store.size("oci/uploads/" + id + "/" + index);
        }
        return total;
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
    int reap(ArtifactStore store) throws IOException {
        Instant cutoff = clock.instant().minus(uploadTtl);
        int reaped = 0;
        for (String id : store.list("oci/upload-sessions")) {
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(SESSIONS + id);
            if (marker.isEmpty()) {
                continue;
            }
            Instant startedAt;
            try {
                startedAt = Instant.ofEpochMilli(Long.parseLong(
                        new String(marker.get().content(), StandardCharsets.UTF_8).trim()));
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
        if (exchange.method().equals("PUT")) {
            if (!reference.startsWith("sha256:") && !isTag(reference)) {
                // A manifest is pushed either by digest (sha256:...) or by tag; a reference that is neither a digest
                // nor a well-formed tag would land as an oci/<name>/tags/<ref> store key, so a '/'- or '..'-laced
                // reference could aim the write at a neighbouring key space - refuse it before storing anything, the
                // tag-side counterpart of the isDigestHex guard on the blob path.
                exchange.respond(400);
                return;
            }
            // writeBlob stores the manifest under blobs/<hex> and returns its digest, so even the manifest goes to
            // storage as a stream rather than being read into memory to be hashed.
            String hex = store.writeBlob(exchange.requestStream());
            String type = exchange.requestHeader("Content-Type");
            store.write("oci/types/" + hex, new ByteArrayInputStream(
                    (type == null ? OCI_MANIFEST : type).getBytes(StandardCharsets.UTF_8)));
            if (!reference.startsWith("sha256:")) {
                linkTag(store, "oci/" + name + "/tags/" + reference, "sha256:" + hex);
            }
            exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
            exchange.setResponseHeader("Location", "/v2/" + name + "/manifests/sha256:" + hex);
            exchange.respond(201);
            return;
        }
        String hex;
        if (reference.startsWith("sha256:")) {
            hex = reference.substring("sha256:".length());
        } else {
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

    private void tags(String name, ArtifactStore store, FormatExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("tags", store.list("oci/" + name + "/tags"));
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    /** The Distribution catalog ({@code GET /v2/_catalog}): every image name that carries at least one tag pointer,
     *  sorted, honouring the API's optional {@code n}/{@code last} paging with a {@code Link} to the next page -
     *  the index through which a registry (and a jenesis repository serving this format) is enumerable, so
     *  migration off this repository works over the format's own protocol. */
    private void catalog(ArtifactStore store, FormatExchange exchange) throws IOException {
        List<String> repositories = new ArrayList<>();
        images(store, "", repositories);
        Collections.sort(repositories);
        String last = exchange.queryParameter("last");
        if (last != null) {
            repositories.removeIf(name -> name.compareTo(last) <= 0);
        }
        String limit = exchange.queryParameter("n");
        int page;
        try {
            page = limit == null ? Integer.MAX_VALUE : Integer.parseInt(limit);
        } catch (NumberFormatException invalid) {
            exchange.respond(400);
            return;
        }
        if (page < repositories.size()) {
            repositories.subList(page, repositories.size()).clear();
            exchange.setResponseHeader("Link", "</v2/_catalog?n=" + page + "&last="
                    + URLEncoder.encode(repositories.getLast(), StandardCharsets.UTF_8) + ">; rel=\"next\"");
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, JSON.writeValueAsString(Map.of("repositories", repositories))
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Collect every image name under the {@code oci/} prefix - a directory with a non-empty {@code tags} child.
     *  The format's own sidecar prefixes ({@code types}, {@code uploads}, {@code upload-sessions}) and the
     *  {@code tags} leaf itself are reserved by this layout and never image-name segments. */
    private void images(ArtifactStore store, String prefix, List<String> names) {
        for (String child : store.list(prefix.isEmpty() ? "oci" : "oci/" + prefix)) {
            if (child.equals("tags") || prefix.isEmpty()
                    && (child.equals("types") || child.equals("uploads") || child.equals("upload-sessions"))) {
                continue;
            }
            String name = prefix.isEmpty() ? child : prefix + "/" + child;
            if (!store.list("oci/" + name + "/tags").isEmpty()) {
                names.add(name);
            }
            images(store, name, names);
        }
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
        String root = upstream.toString();
        URI url = URI.create((root.endsWith("/") ? root : root + "/") + "v2/" + name
                + (manifest ? "/manifests/" : "/blobs/") + reference);
        if (manifest) {
            return proxyManifest(name, reference, accept, exchange, store, url, fetcher);
        }
        // A blob carries no metadata to inspect, so it streams straight from upstream to storage: writeBlob digests it
        // as it stores it, and the digest is checked against the requested one afterwards.
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
        if (reference.startsWith("sha256:") && !hex.equals(hex(reference))) {
            return false;
        }
        store.write("blobs/" + hex, new ByteArrayInputStream(body));
        String type = fetched.get().header("Content-Type");
        store.write("oci/types/" + hex, new ByteArrayInputStream(
                (type == null ? OCI_MANIFEST : type).getBytes(StandardCharsets.UTF_8)));
        if (!reference.startsWith("sha256:")) {
            if (!isTag(reference)) {
                return false; // a non-tag reference must not become a tags/ store key - let the local 404 stand
            }
            linkTag(store, "oci/" + name + "/tags/" + reference, "sha256:" + hex);
        }
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
        String token = token(challenge.substring("Bearer ".length()), fetcher);
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
            token = token(challenge.substring("Bearer ".length()), fetcher);
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
        Iterator<String> repositories = paged(URI.create(base + "v2/_catalog"), "repositories", fetcher);
        Set<String> emitted = new HashSet<>();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(repositories, Spliterator.ORDERED), false)
                .flatMap(name -> {
                    try {
                        Iterator<String> tags = paged(URI.create(base + "v2/" + name + "/tags/list"), "tags", fetcher);
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

    /** Add the coordinates one manifest transitively references, depth-first: an index's per-platform manifests
     *  (each expanded then added by digest), a manifest's config and layers as blobs - each digest once per walk. */
    private void expand(URI base, String name, byte[] manifest, List<Coordinate> coordinates, Set<String> emitted,
                        ProxyFormat.Fetcher fetcher) throws IOException {
        JsonNode node = JSON.readTree(new String(manifest, StandardCharsets.UTF_8));
        if (node.has("manifests")) {
            for (JsonNode child : node.path("manifests")) {
                String digest = child.path("digest").asString(null);
                if (digest == null || !emitted.add(digest)) {
                    continue;
                }
                expand(base, name, manifest(base, name, digest, fetcher), coordinates, emitted, fetcher);
                coordinates.add(new Coordinate("v2/" + name + "/manifests/" + digest,
                        URI.create(base + "v2/" + name + "/manifests/" + digest),
                        Map.of("Accept", MANIFEST_ACCEPT)));
            }
            return;
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
    private Iterator<String> paged(URI first, String field, ProxyFormat.Fetcher fetcher) throws IOException {
        Page initial = page(first, field, fetcher);
        return new Iterator<>() {
            private Page current = initial;
            private int index;

            @Override
            public boolean hasNext() {
                while (index == current.values().size() && current.next() != null) {
                    try {
                        current = page(current.next(), field, fetcher);
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

    private Page page(URI url, String field, ProxyFormat.Fetcher fetcher) throws IOException {
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

    private String token(String challenge, ProxyFormat.Fetcher fetcher) throws IOException {
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
        Optional<ProxyFormat.Fetched> response = fetcher.fetch(URI.create(url.toString()), Map.of());
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
}
