package build.jenesis.repository.store;

import module java.base;

/**
 * Decouples the artifact bytes from their publication, format-neutrally. Each uploaded blob is stored once,
 * content-addressed by its SHA-256 ({@code blobs/<hash>}), so identical bytes published under several paths dedupe to
 * one object and live independently of any path. A publication is a small pointer ({@code publish/<request-path> ->
 * <hash>}); several paths can point at the same blob (the two Java layouts, a deduped coordinate, a latest mirror),
 * which is how a republish is just a pointer update. This primitive knows nothing of any layout: a format decides what
 * to publish where, and cross-publishing one layout's view into another's is a concern of the format modules, not of
 * this storage primitive.
 */
public final class Publication {

    private static final System.Logger LOGGER = System.getLogger(Publication.class.getName());

    /** The ceiling on a {@link PublishInterceptor.Content#sibling} read. A sibling is small published metadata a gate
     *  inspects beside the artifact (a jar reading its POM, a package reading its manifest) - never a whole artifact -
     *  so the buffered read is capped at a few MiB. Past it the read fails loudly rather than materialising an
     *  arbitrarily large blob into the heap, which would let a screen be turned into an out-of-memory lever. */
    private static final int LARGEST_SIBLING = 8 * 1024 * 1024;

    /** The screen chain, discovered once at class load like {@code MavenFormat.MODULE_VIEWS} - empty in the free
     *  edition (no provider on the module path), so {@link #publish} is a plain store-then-link there. */
    private static final List<PublishInterceptor> DISCOVERED = ServiceLoader.load(PublishInterceptor.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    /** The after-commit observers, discovered the same way - the hook class with no say in any verdict. */
    private static final List<PublicationObserver> OBSERVERS = ServiceLoader.load(PublicationObserver.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    private final ArtifactStore store;
    private final List<PublishInterceptor> interceptors;
    private final List<PublicationObserver> observers;

    public Publication(ArtifactStore store) {
        this(store, DISCOVERED, OBSERVERS);
    }

    /** A publication whose upload post-processing runs an explicit screen list rather than the
     *  {@code ServiceLoader}-discovered one - the seam an embedder uses to inject screens that are not on the module
     *  path. Either way the chain runs sorted by {@link PublishInterceptor#order()}, ties keeping their given order. */
    public Publication(ArtifactStore store, List<PublishInterceptor> interceptors) {
        this(store, interceptors, OBSERVERS);
    }

    /** The fully explicit seam: screens and after-commit observers both injected rather than discovered. */
    public Publication(ArtifactStore store, List<PublishInterceptor> interceptors, List<PublicationObserver> observers) {
        this.store = store;
        this.interceptors = interceptors.stream().sorted(Comparator.comparingInt(PublishInterceptor::order)).toList();
        this.observers = observers;
    }

    /** The blob key ({@code blobs/<hash>}) a path resolves to when it is published and the blob is present - what a
     *  streaming {@code GET} sets its {@code Content-Length} from (through {@link ArtifactStore#size}) and then copies
     *  to the response (through {@link ArtifactStore#read}), instead of buffering the blob to learn its length. Empty
     *  when nothing is published there, the blob is gone, or a screen {@link PublishInterceptor#withheld withholds}
     *  the path - the quarantine read side, so a verdict that changes after the fact retracts a linked artifact from
     *  every serving surface without touching its pointer. */
    public Optional<String> located(String requestPath) throws IOException {
        for (PublishInterceptor interceptor : interceptors) {
            if (interceptor.withheld(requestPath, store)) {
                return Optional.empty();
            }
        }
        return blob(requestPath).map(hash -> "blobs/" + hash).filter(store::exists);
    }

    /** Stream content once, content-addressed while it is read, and return its hash - so a large artifact goes from the
     *  network to storage without being buffered whole in memory. The primitive a staging deploy or a cross-publish
     *  uses to hold bytes before any view points at them. */
    public String storeBlob(InputStream content) throws IOException {
        return store.writeBlob(content);
    }

    /** Point a request path at an already-stored blob - the primitive promotion and cross-publishing use to publish a
     *  blob under another view without re-uploading it. The pointer is the product's most load-bearing small object,
     *  so a compare-and-set conflict re-reads the token and retries (the bounded idiom every other load-bearing
     *  pointer write uses) rather than silently dropping the losing write: a concurrent republish of the same path
     *  resolves to last-writer-wins - the same outcome the two writes would have had a moment apart - and a caller
     *  whose link cannot land is told so instead of believing it published. Once the pointer lands, any garbage
     *  collector's {@code gc/condemned/<hash>} marker on the blob is cleared - identical content dedupes to one
     *  blob, so a "new" publish may link a blob a collector already judged unreferenced, and clearing the marker on
     *  the write path (every link site: publish, quarantine, promotion, cross-publish) un-condemns it before the
     *  collecting sweep's final marker re-read. One existence probe per link, a no-op wherever collection never
     *  condemned the blob; the marker key is the store-layout convention the {@code gc} SPI documents. */
    public void link(String requestPath, String hash) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            Object token = store.readVersioned("publish" + requestPath).map(ArtifactStore.Versioned::token).orElse(null);
            if (store.writeVersioned("publish" + requestPath, hash.getBytes(StandardCharsets.UTF_8), token)) {
                String condemned = "gc/condemned/" + hash;
                if (store.exists(condemned)) {
                    store.delete(condemned);
                }
                return;
            }
        }
        throw new IOException("could not link publish" + requestPath + " after repeated version conflicts");
    }

    /** The content hash a path currently points at, or empty if nothing is published there. */
    public Optional<String> blob(String requestPath) throws IOException {
        return store.readVersioned("publish" + requestPath)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim());
    }

    /** Remove a single published pointer; the blob it referenced is left for a later garbage collection, since
     *  another pointer may still reference it. Every discovered {@link PublicationObserver} is notified of the
     *  removal ({@code onDeleted}, once per removed pointer) with what this site knows - the request path and the
     *  blob hash the pointer named, read before the delete; no coordinate, since this primitive knows no layouts -
     *  and a failing observer is logged and contained exactly as on a publish, never blocking the removal. */
    public void unpublish(String requestPath) throws IOException {
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned("publish" + requestPath);
        if (pointer.isEmpty()) {
            return;
        }
        store.delete("publish" + requestPath);
        String named = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
        ArtifactDescriptor removed = ArtifactDescriptor.at(null, requestPath);
        notifyDeleted(hash(named) ? removed.withBlob(named, -1L) : removed);
    }

    /** Remove the pointer at {@code described.path()} exactly like {@link #unpublish(String)}, but notify the
     *  observers with the caller's layout-enriched descriptor - ecosystem, coordinate and version filled in where
     *  this neutral primitive cannot - completing its blob identity from the pointer when the caller left it
     *  unset. The seam a layout-aware eviction uses so a removal event carries what the eviction already knows. */
    public void unpublish(ArtifactDescriptor described) throws IOException {
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned("publish" + described.path());
        if (pointer.isEmpty()) {
            return;
        }
        store.delete("publish" + described.path());
        String named = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
        notifyDeleted(described.hash() == null && hash(named) ? described.withBlob(named, described.size()) : described);
    }

    /** Notify every observer of a serving-pointer removal this primitive did not perform - the seam a layout-aware
     *  eviction calls once per pointer it deletes in a format's <em>own</em> namespace (a blobs-namespace key
     *  outside {@code publish/}), so those removals are observed exactly like an {@link #unpublish}. Failures are
     *  logged and contained like every observer notification; nothing is read or deleted here - the caller already
     *  removed the pointer and describes it. */
    public void deleted(ArtifactDescriptor removed) {
        notifyDeleted(removed);
    }

    /** Whether a pointer's content is the lower-case SHA-256 hex a {@link #link} writes - the only shape carried
     *  into a removal descriptor's blob identity, so a corrupt pointer never masquerades as a hash. */
    private static boolean hash(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    private void notifyDeleted(ArtifactDescriptor removed) {
        for (PublicationObserver observer : observers) {
            try {
                observer.onDeleted(removed, store);
            } catch (Exception exception) {
                LOGGER.log(System.Logger.Level.WARNING, "publication observer "
                        + observer.getClass().getName() + " failed for removal of " + removed.path(), exception);
            }
        }
    }

    /** The outcome of a gated publish: the disposition the interceptor chain reached and the SHA-256 the blob was
     *  stored under - present whatever the disposition, since the blob is written content-addressed before the gate. */
    public record Published(PublishInterceptor.Disposition disposition, String hash) {
    }

    /**
     * Store an artifact streamed content-addressed, run the {@link PublishInterceptor} chain over its neutral
     * {@link ArtifactDescriptor}, and route its pointer by the strongest {@link PublishInterceptor.Disposition} across
     * the chain: {@code ACCEPT} links {@code artifact.path()}, {@code QUARANTINE} links {@code "/quarantine" + path}
     * (stored but not served), {@code REJECT} links nothing (the orphaned blob is left for garbage collection). The
     * blob is inert until a pointer references it, so the chain gates before the first link - nothing is buffered and
     * there is no published-then-retracted window. With the default empty chain this is exactly a
     * {@link #storeBlob} followed by a {@link #link}. Once an accepted artifact is linked, every discovered
     * {@link PublicationObserver} is notified - only then, so an observer never sees a quarantined or rejected
     * publish - with any observer failure logged and contained, never unlinking the artifact or failing the upload.
     */
    public Published publish(ArtifactDescriptor artifact, InputStream content) throws IOException {
        return route(artifact, content, true);
    }

    /**
     * Store an upload content-addressed and run the {@link PublishInterceptor} chain over its neutral
     * {@link ArtifactDescriptor} <em>without publishing it</em>: an accepted upload links no pointer - the caller
     * owns the accepted write, storing the content into its own layout from the returned hash - while a quarantined
     * one is still diverted to the {@code /quarantine} view for review and a rejected one leaves only the
     * unreferenced blob for garbage collection. This is the seam for a format-external gate choreography (a serving
     * surface screening an upload before it hands the stored body to the format that lays it out). The interceptors'
     * {@link PublishInterceptor#committed} notifications fire; the after-commit {@link PublicationObserver}s do not,
     * since nothing was published yet - they ride the actual publish, if the caller makes one.
     */
    public Published screen(ArtifactDescriptor artifact, InputStream content) throws IOException {
        return route(artifact, content, false);
    }

    private Published route(ArtifactDescriptor artifact, InputStream content, boolean publish) throws IOException {
        String hash = storeBlob(content);
        ArtifactDescriptor stored = artifact.withBlob(hash, store.size("blobs/" + hash));
        PublishInterceptor.Content access = access(hash);
        PublishInterceptor.Disposition disposition = PublishInterceptor.Disposition.ACCEPT;
        for (PublishInterceptor interceptor : interceptors) {
            PublishInterceptor.Disposition verdict = interceptor.assess(stored, access);
            if (verdict.compareTo(disposition) > 0) {
                disposition = verdict;
            }
        }
        switch (disposition) {
            case ACCEPT -> {
                if (publish) {
                    link(artifact.path(), hash);
                }
            }
            case QUARANTINE -> link("/quarantine" + artifact.path(), hash);
            case REJECT -> {
            }
        }
        for (PublishInterceptor interceptor : interceptors) {
            interceptor.committed(stored, disposition, store);
        }
        if (publish && disposition == PublishInterceptor.Disposition.ACCEPT) {
            for (PublicationObserver observer : observers) {
                try {
                    observer.onPublished(stored, store);
                } catch (Exception exception) {
                    LOGGER.log(System.Logger.Level.WARNING, "publication observer "
                            + observer.getClass().getName() + " failed for " + stored.path(), exception);
                }
            }
        }
        return new Published(disposition, hash);
    }

    /** A read view over the just-stored blob and its published siblings, handed to each interceptor so a gate reads
     *  the artifact back from storage rather than the store holding the upload in memory to show it. */
    private PublishInterceptor.Content access(String hash) {
        return new PublishInterceptor.Content() {
            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public InputStream open() throws IOException {
                return store.open("blobs/" + hash);
            }

            @Override
            public Optional<byte[]> sibling(String path) throws IOException {
                Optional<String> key = located(path);
                if (key.isEmpty()) {
                    return Optional.empty();
                }
                // Read at most LARGEST_SIBLING + 1 bytes so the heap is bounded whatever the blob's real size: a
                // sibling read is small metadata, and an oversized one is an anomaly a gate must be told about, not
                // silently fed. readNBytes never buffers more than the cap, so the over-limit blob never lands whole
                // in memory before we notice.
                try (InputStream in = store.open(key.get())) {
                    byte[] bytes = in.readNBytes(LARGEST_SIBLING + 1);
                    if (bytes.length > LARGEST_SIBLING) {
                        throw new IOException("sibling " + path + " exceeds the " + LARGEST_SIBLING
                                + "-byte cap for a sibling metadata read");
                    }
                    return Optional.of(bytes);
                }
            }
        };
    }
}
