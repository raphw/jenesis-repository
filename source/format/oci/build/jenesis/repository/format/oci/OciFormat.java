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
        String key = "blobs/" + hex(digest);
        if (!store.exists(key)) {
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
            String digest = exchange.queryParameter("digest");
            if (digest != null) {
                store(digest, exchange.requestStream(), store, name, exchange);
                return;
            }
            String id = UUID.randomUUID().toString();
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

    /** Drop every chunk object of a finalized (or abandoned) upload session. */
    private static void cleanup(ArtifactStore store, String id) throws IOException {
        for (String index : store.list("oci/uploads/" + id)) {
            store.delete("oci/uploads/" + id + "/" + index);
        }
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
            // writeBlob stores the manifest under blobs/<hex> and returns its digest, so even the manifest goes to
            // storage as a stream rather than being read into memory to be hashed.
            String hex = store.writeBlob(exchange.requestStream());
            String type = exchange.requestHeader("Content-Type");
            store.write("oci/types/" + hex, new ByteArrayInputStream(
                    (type == null ? OCI_MANIFEST : type).getBytes(StandardCharsets.UTF_8)));
            if (!reference.startsWith("sha256:")) {
                String key = "oci/" + name + "/tags/" + reference;
                Object token = store.readVersioned(key).map(ArtifactStore.Versioned::token).orElse(null);
                store.writeVersioned(key, ("sha256:" + hex).getBytes(StandardCharsets.UTF_8), token);
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
        String key = "blobs/" + hex;
        if (!store.exists(key)) {
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
            String key = "oci/" + name + "/tags/" + reference;
            Object token = store.readVersioned(key).map(ArtifactStore.Versioned::token).orElse(null);
            store.writeVersioned(key, ("sha256:" + hex).getBytes(StandardCharsets.UTF_8), token);
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

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
