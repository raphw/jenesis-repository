package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Maven layout ({@code /maven/...}): a {@code PUT} stores the blob content-addressed through the shared
 * {@link Publication} store, and a {@code GET} serves it or generates {@code maven-metadata.xml} on read through
 * {@link MavenMetadata} (a metadata upload is dropped, since the metadata is derived). When the uploaded artifact is a
 * modular jar, it is cross-published into the Jenesis module layout: this format reads the module name and hands it to
 * the {@link ModuleView} the Jenesis format provides (discovered with {@link ServiceLoader}), so a client resolving by
 * module name reaches the same blob - the bridge between the two layouts, exposed only between them and never on the
 * public SPI. Discovered like any other format; the core knows nothing of it.
 */
public final class MavenFormat implements RepositoryFormat, ProxyFormat, ArtifactLayout {

    private static final List<ModuleView> MODULE_VIEWS = ServiceLoader.load(ModuleView.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    /** The package-ecosystem name the neutral descriptor carries - the OSV name "Maven" that advisory feeds and
     *  quality inspectors key on - distinct from {@link #name()} "maven", the format id that routes the {@code /maven/}
     *  paths. Any consumer of a Maven artifact reports the same ecosystem, whichever edition it runs in. */
    public static final String ECOSYSTEM = "Maven";

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/maven/");
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        return descriptor(path);
    }

    @Override
    public List<String> paths(String coordinate, String version) {
        int colon = coordinate.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        String group = coordinate.substring(0, colon).replace('.', '/');
        String artifact = coordinate.substring(colon + 1);
        return List.of("/maven/" + group + "/" + artifact + "/" + version);
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        List<String> primary = paths(coordinate, version);
        if (primary.isEmpty()) {
            return primary;
        }
        int colon = coordinate.indexOf(':');
        String artifact = coordinate.substring(colon + 1);
        String mavenDir = primary.getFirst();
        List<String> paths = new ArrayList<>(primary);
        // Also the module view this format cross-published for a modular jar: read the module name back from the
        // stored jar (the same read publish did), so a cleanup that unpublishes this version removes its /module/
        // mirror too and the shared blob becomes unreferenced. Best-effort: no jar, no module, no mirror. This is the
        // one store read, and it is why a read path (a console search) must call the store-free overload instead.
        try {
            Publication publication = new Publication(store);
            Optional<String> key = publication.located(mavenDir + "/" + artifact + "-" + version + ".jar");
            if (key.isPresent()) {
                String module;
                try (InputStream in = store.open(key.get())) {
                    module = JavaLayout.moduleName(in);
                }
                if (module != null) {
                    paths.add("/module/" + module + "/" + version);
                }
            }
        } catch (IOException _) {
            // best-effort; the /maven/ pointers still evict and the blob is reclaimed if now unreferenced
        }
        return paths;
    }

    /** The neutral descriptor of a {@code /maven/...} path, or empty for generated metadata (nothing to describe): a
     *  full coordinate maps to {@code group:artifact} + version, and this is the one place the {@code -SNAPSHOT}
     *  prerelease rule lives; a path that is not a full coordinate (a checksum root) carries the ecosystem only. */
    private static Optional<ArtifactDescriptor> descriptor(String path) {
        if (MavenMetadata.isMetadataRequest(path)) {
            return Optional.empty();
        }
        String[] coordinate = JavaLayout.mavenCoordinate(path);
        if (coordinate == null) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, coordinate[0] + ":" + coordinate[1], coordinate[2],
                path, null, coordinate[2].endsWith("-SNAPSHOT"), null, -1L));
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        if (exchange.method().equals("PUT")) {
            if (MavenMetadata.isMetadataRequest(path)) {
                exchange.respond(201);
                return;
            }
            exchange.respond(status(publish(store, path, exchange.requestStream()).disposition()));
            return;
        }
        Optional<byte[]> generated = new MavenMetadata(store).serve(path);
        if (generated.isPresent()) {
            exchange.respond(200, generated.get());
            return;
        }
        Optional<String> key = new Publication(store).located(path);
        if (key.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (OutputStream out = exchange.respond(200, store.size(key.get()))) {
            store.read(key.get(), out);
        }
    }

    /** Store the artifact through the gated {@link Publication#publish} (streamed straight to storage, then the upload
     *  post-processing chain decides accept/quarantine/reject), and - only when it is accepted and is a modular jar -
     *  cross-publish its module view through the Jenesis layout, reading the module name back from the just-stored blob
     *  rather than buffering the jar in memory. A quarantined or rejected jar never gains a module-view pointer, so it
     *  does not resolve by module name either. Returns the publish outcome so the caller maps it to an HTTP status. */
    public static Publication.Published publish(ArtifactStore store, String path, InputStream body) throws IOException {
        Publication publication = new Publication(store);
        ArtifactDescriptor descriptor = descriptor(path).orElseGet(() -> ArtifactDescriptor.at(ECOSYSTEM, path));
        Publication.Published published = publication.publish(descriptor, body);
        String[] coordinate = JavaLayout.mavenCoordinate(path);
        if (published.disposition() != PublishInterceptor.Disposition.ACCEPT
                || !path.endsWith(".jar") || coordinate == null) {
            return published;
        }
        String module;
        try (InputStream in = store.open("blobs/" + published.hash())) {
            module = JavaLayout.moduleName(in);
        }
        if (module == null) {
            return published;
        }
        for (ModuleView view : MODULE_VIEWS) {
            view.publish(module, coordinate[2], published.hash(), store);
        }
        return published;
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

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://repo1.maven.org/maven2/"));
    }

    /**
     * Proxy a {@code /maven/} miss to the upstream Maven repository (Maven Central). Artifacts (jars, poms and their
     * checksums) are immutable and cached, and a cached modular jar is cross-published like a local one;
     * {@code maven-metadata.xml} is generated locally from the version folders, so it is not proxied.
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
        String prefix = root.endsWith("/") ? root : root + "/";
        Optional<ProxyFormat.Download> fetched = fetcher.download(URI.create(prefix + rest), Map.of());
        if (fetched.isEmpty()) {
            return false;
        }
        try (ProxyFormat.Download download = fetched.get()) {
            if (download.status() != 200) {
                return false;
            }
            if (isChecksum(rest)) {
                publish(store, path, download.body());
            } else {
                // Verify a proxied artifact against the upstream-published SHA-1, so a body corrupted or tampered
                // between the upstream and here is never left cached and served. The digest is computed as the blob
                // streams to storage; the tiny checksum sibling is fetched afterwards, so it never delays the artifact.
                MessageDigest sha1 = sha1();
                publish(store, path, new DigestInputStream(download.body(), sha1));
                String expected = upstreamSha1(fetcher, URI.create(prefix + rest + ".sha1"));
                if (expected != null && !expected.equalsIgnoreCase(HexFormat.of().formatHex(sha1.digest()))) {
                    new Publication(store).unpublish(path);
                    return false;
                }
            }
        }
        handle(exchange, store);
        return true;
    }

    /** A Maven checksum or signature sibling - itself the integrity token, so it is proxied as-is, not re-verified. */
    private static boolean isChecksum(String rest) {
        return rest.endsWith(".sha1") || rest.endsWith(".md5") || rest.endsWith(".sha256")
                || rest.endsWith(".sha512") || rest.endsWith(".asc");
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The upstream SHA-1 for an artifact, read from its {@code .sha1} sibling (the 40-hex digest, optionally followed
     *  by a filename); {@code null} when the upstream publishes none, so an artifact without a checksum is proxied
     *  unverified rather than refused. */
    private static String upstreamSha1(ProxyFormat.Fetcher fetcher, URI sha1) throws IOException {
        Optional<ProxyFormat.Fetched> response = fetcher.fetch(sha1, Map.of());
        if (response.isEmpty() || response.get().status() != 200) {
            return null;
        }
        String body = new String(response.get().body(), StandardCharsets.UTF_8).trim();
        int space = body.indexOf(' ');
        String hex = space > 0 ? body.substring(0, space) : body;
        return hex.length() == 40 && hex.chars().allMatch(c -> Character.digit(c, 16) >= 0) ? hex : null;
    }
}
