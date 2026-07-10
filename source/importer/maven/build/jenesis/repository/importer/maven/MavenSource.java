package build.jenesis.repository.importer.maven;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportSource;

/**
 * An {@link ImportSource} over <em>any</em> repository serving the Maven layout on plain HTTP - the vendor-neutral
 * read half of a Maven migration, covering Nexus, Artifactory, a plain httpd/nginx autoindex, a static bucket, or
 * another jenesis, without a vendor API. Three enumeration strategies stack by availability. Where the server exposes
 * a directory listing (an autoindex page), the tree is walked recursively in deterministic depth-first order, every
 * artifact file reported with format {@code maven} at its layout-relative path; {@code maven-metadata.xml} and
 * checksum sidecars are not imported (the target regenerates or derives them). A root that answers a landing page
 * instead of a listing (a Nexus repository root) is followed one hop to the HTML index the page itself advertises,
 * and an index row that links its file at a canonical download URL under another root (Nexus again) is walked as
 * that file - both read off the pages, so the walk stays vendor-neutral. Where listing is disabled, the walk
 * falls back to the repository index Nexus-style servers publish ({@code .index/nexus-maven-repository-index.gz}),
 * streaming its records for coordinates - and refreshes each coordinate through its {@code maven-metadata.xml},
 * importing versions the index lags behind as their pom plus the primary artifact the pom's packaging names. With
 * neither a listing nor an index the walk fails with a clear message. Each artifact streams lazily through the same
 * {@link ProxyFormat.Fetcher} the proxy uses, so an import is tested without the network.
 *
 * <p>The walk checkpoints an opaque cursor after each fully-consumed batch: {@code tree:<directory>} after each
 * completed subtree of the listing walk, {@code index:<records>} periodically through the index stream and
 * {@code meta:<coordinate>} after each refreshed coordinate - so an interrupted migration resumes without re-importing
 * what a prior run completed (and the content-addressed store dedupes anything a resumed run repeats).
 */
public final class MavenSource implements ImportSource {

    private static final String FORMAT = "maven";
    private static final String TREE = "tree:", INDEX = "index:", META = "meta:";
    private static final int MAX_DEPTH = 64;
    private static final int INDEX_CHECKPOINT_INTERVAL = 512;

    private final URI base;
    private final String repository;
    private final ProxyFormat.Fetcher fetcher;
    private final String authorization;
    private final String cursor;

    public MavenSource(URI base, String repository, ProxyFormat.Fetcher fetcher) {
        this(base, repository, fetcher, null, null);
    }

    private MavenSource(URI base, String repository, ProxyFormat.Fetcher fetcher, String authorization, String cursor) {
        this.base = base;
        this.repository = repository;
        this.fetcher = fetcher;
        this.authorization = authorization;
        this.cursor = cursor;
    }

    /** Authenticate the listings and downloads with HTTP basic credentials (a repository user and password or token). */
    public MavenSource withCredentials(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new MavenSource(base, repository, fetcher, "Basic " + token, cursor);
    }

    /** Resume the walk from a cursor a prior run checkpointed. */
    public MavenSource from(String cursor) {
        return new MavenSource(base, repository, fetcher, authorization, cursor);
    }

    /** Whether the repository root answers HTTP at all - any status counts, only a transport failure (an unknown
     *  host, a refused connection) does not - so a submission naming a bad URL is rejected up front as a bad request
     *  rather than failing asynchronously. */
    public boolean reachable() {
        try {
            return fetcher.fetch(root(), headers()).isPresent();
        } catch (IOException unreachable) {
            return false;
        }
    }

    @Override
    public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
        URI root = root();
        if (cursor != null && (cursor.startsWith(INDEX) || cursor.startsWith(META))) {
            walkIndex(consumer, checkpoint, root);
            return;
        }
        ProxyFormat.Fetched listing = get(root);
        List<HtmlListing.Entry> entries = listing.status() == 200
                ? HtmlListing.parse(root, "", new String(listing.body(), StandardCharsets.UTF_8))
                : List.of();
        if (entries.isEmpty() && listing.status() == 200) {
            // A Nexus repository root answers a landing page, not a listing - but the page links its actual HTML
            // index (a same-authority link named like the root itself), so follow that one advertised hop.
            URI pointer = HtmlListing.listingPointer(root, new String(listing.body(), StandardCharsets.UTF_8));
            if (pointer != null) {
                ProxyFormat.Fetched hopped = get(pointer);
                if (hopped.status() == 200) {
                    entries = HtmlListing.parse(pointer, "", new String(hopped.body(), StandardCharsets.UTF_8));
                }
            }
        }
        if (!entries.isEmpty()) {
            String resume = cursor != null && cursor.startsWith(TREE) ? cursor.substring(TREE.length()) : null;
            walkTree(consumer, checkpoint, "", "", entries, resume, 1);
            checkpoint.reached(null);
        } else if (get(URI.create(root + RepositoryIndex.PROPERTIES)).status() == 200) {
            walkIndex(consumer, checkpoint, root);
        } else {
            throw new IOException("Cannot enumerate " + root + ": the server exposes no directory listing (status "
                    + listing.status() + ") and publishes no repository index (" + RepositoryIndex.PROPERTIES
                    + ") - enable directory listing on the source or have it publish a Maven repository index");
        }
    }

    /**
     * The directory-listing walk: each directory's entries are visited in sorted order (files and subdirectories
     * interleaved), so the depth-first emission order is deterministic and a resume cursor - the last fully-consumed
     * directory - prunes exactly the subtrees a prior run completed. Server-internal dot entries ({@code .index/},
     * {@code .meta/}) are not repository content and are not descended into.
     */
    private void walkTree(Asset consumer, Checkpoint checkpoint, String path, String rawPath,
                          List<HtmlListing.Entry> entries, String resume, int depth) throws IOException {
        for (HtmlListing.Entry entry : entries) {
            if (entry.name().startsWith(".")) {
                continue;
            }
            if (entry.directory()) {
                String directory = path + entry.name() + "/";
                if (resume != null && (directory.startsWith(resume)
                        || walkedBefore(directory, resume) && !resume.startsWith(directory))) {
                    continue;   // the whole subtree was consumed by the interrupted run
                }
                if (depth >= MAX_DEPTH) {
                    throw new IOException("Directory tree exceeds depth " + MAX_DEPTH + " at " + entry.target());
                }
                ProxyFormat.Fetched listing = get(entry.target());
                if (listing.status() != 200) {
                    throw new IOException("Directory listing failed (" + listing.status() + ") for " + entry.target());
                }
                walkTree(consumer, checkpoint, directory, rawPath + entry.raw() + "/",
                        HtmlListing.parse(entry.target(), rawPath + entry.raw() + "/",
                                new String(listing.body(), StandardCharsets.UTF_8)),
                        resume, depth + 1);
                checkpoint.reached(TREE + directory);   // the subtree is fully consumed - a resume skips past it
            } else if (imported(entry.name())) {
                String file = path + entry.name();
                if (resume == null || !walkedBefore(file, resume)) {
                    consumer.accept(FORMAT, file, () -> open(entry.target()));
                }
            }
        }
    }

    /** Whether {@code path} precedes {@code cursor} in the walk's depth-first order. Plain string order would place
     *  {@code foo-bar} before {@code foo/x} although the walk descends into {@code foo/} first, so the separator
     *  ranks below every other character. */
    private static boolean walkedBefore(String path, String cursor) {
        for (int index = 0; index < Math.min(path.length(), cursor.length()); index++) {
            char left = path.charAt(index), right = cursor.charAt(index);
            if (left != right) {
                return left == '/' || right != '/' && left < right;
            }
        }
        return path.length() < cursor.length();
    }

    /** Repository content worth importing: not the {@code maven-metadata.xml} the target regenerates from the
     *  imported version folders, and not a checksum sidecar (the store derives checksums from the blob itself). */
    private static boolean imported(String name) {
        return !name.startsWith("maven-metadata.xml")
                && !name.endsWith(".sha1") && !name.endsWith(".md5")
                && !name.endsWith(".sha256") && !name.endsWith(".sha512");
    }

    /**
     * The listing-less fallback: stream the published repository index once, importing each record's file (a
     * classifier-less record also implies its pom, emitted alongside), then refresh every seen coordinate through its
     * {@code maven-metadata.xml} - the index is published in batches and lags what the repository actually holds, so
     * the metadata is the authority on versions once a coordinate is known. A resume replays the index stream without
     * re-importing (rebuilding the coordinate set costs no downloads) and continues where the cursor points.
     */
    private void walkIndex(Asset consumer, Checkpoint checkpoint, URI root) throws IOException {
        boolean refreshing = cursor != null && cursor.startsWith(META);
        long resumeRecords = cursor != null && cursor.startsWith(INDEX)
                ? Long.parseLong(cursor.substring(INDEX.length()))
                : 0;
        String resumeCoordinate = refreshing ? cursor.substring(META.length()) : null;
        SortedMap<String, Set<String>> indexed = new TreeMap<>();
        Set<String> pomEmitted = new HashSet<>();
        URI url = URI.create(root + RepositoryIndex.FULL);
        ProxyFormat.Download download = fetcher.download(url, headers())
                .orElseThrow(() -> new IOException("No response from " + url));
        if (download.status() != 200) {
            download.close();
            throw new IOException("Repository index download failed (" + download.status() + ") for " + url);
        }
        long consumed = 0;
        try (RepositoryIndex index = new RepositoryIndex(download.body())) {
            Map<String, String> record;
            while ((record = index.next()) != null) {
                consumed++;
                RepositoryIndex.Gav gav = RepositoryIndex.coordinate(record);
                if (gav != null) {
                    indexed.computeIfAbsent(gav.artifactPath(), key -> new HashSet<>()).add(gav.version());
                    if (!refreshing && consumed > resumeRecords) {
                        String path = gav.path(), pom = gav.pomPath();
                        if (!path.equals(pom)) {
                            emit(consumer, root, path);
                        }
                        if (pom != null && pomEmitted.add(pom)) {
                            emit(consumer, root, pom);
                        }
                    }
                }
                if (!refreshing && consumed % INDEX_CHECKPOINT_INTERVAL == 0 && consumed > resumeRecords) {
                    checkpoint.reached(INDEX + consumed);
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : indexed.entrySet()) {
            if (resumeCoordinate != null && entry.getKey().compareTo(resumeCoordinate) <= 0) {
                continue;   // refreshed by the interrupted run
            }
            refresh(consumer, root, entry.getKey(), entry.getValue());
            checkpoint.reached(META + entry.getKey());
        }
        checkpoint.reached(null);
    }

    /** The {@code maven-metadata.xml} refresh of one coordinate ({@code <group-path>/<artifact>}): a version the
     *  metadata lists beyond the index's records is imported as its pom plus the primary artifact the pom's
     *  packaging names (classifier sidecars are unknowable without a listing - honestly scoped). */
    private void refresh(Asset consumer, URI root, String coordinate, Set<String> indexed) throws IOException {
        ProxyFormat.Fetched metadata = get(URI.create(root + coordinate + "/maven-metadata.xml"));
        if (metadata.status() != 200) {
            return;   // no metadata published for this coordinate - its index records were already imported
        }
        String artifact = coordinate.substring(coordinate.lastIndexOf('/') + 1);
        for (String version : MavenXml.versions(metadata.body())) {
            if (indexed.contains(version) || !RepositoryIndex.safe(version)) {
                continue;
            }
            String prefix = coordinate + "/" + version + "/" + artifact + "-" + version;
            ProxyFormat.Fetched pom = get(URI.create(root + prefix + ".pom"));
            if (pom.status() != 200) {
                continue;   // the metadata names a version the repository no longer serves
            }
            consumer.accept(FORMAT, prefix + ".pom", () -> new ByteArrayInputStream(pom.body()));
            String packaging = MavenXml.packaging(pom.body());
            if (packaging != null && !packaging.equals("pom")) {
                emit(consumer, root, prefix + "." + RepositoryIndex.extension(packaging));
            }
        }
    }

    private void emit(Asset consumer, URI root, String path) throws IOException {
        consumer.accept(FORMAT, path, () -> open(URI.create(root + path)));
    }

    /** The walk's root: the base URL with the repository appended as a path ({@code .} or blank when the URL already
     *  points at the tree), always with a trailing slash so relative listing links resolve against it. */
    private URI root() {
        StringBuilder url = new StringBuilder(base.toString());
        while (!url.isEmpty() && url.charAt(url.length() - 1) == '/') {
            url.setLength(url.length() - 1);
        }
        String path = repository == null ? "" : repository;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.isEmpty() && !path.equals(".")) {
            url.append('/').append(path);
        }
        return URI.create(url.append('/').toString());
    }

    private InputStream open(URI url) throws IOException {
        ProxyFormat.Download download = fetcher.download(url, headers())
                .orElseThrow(() -> new IOException("No response from " + url));
        if (download.status() != 200) {
            download.close();
            throw new IOException("Download failed (" + download.status() + ") for " + url);
        }
        return download.body();
    }

    private ProxyFormat.Fetched get(URI url) throws IOException {
        return fetcher.fetch(url, headers()).orElseThrow(() -> new IOException("No response from " + url));
    }

    private Map<String, String> headers() {
        return authorization == null ? Map.of() : Map.of("Authorization", authorization);
    }
}
