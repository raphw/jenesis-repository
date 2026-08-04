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
 *
 * <p>The upload choreography runs at an ingress <em>edge</em> (a deploy controller, a batch explode, an import walk),
 * not inside a format: {@link #screen} stores the streamed body content-addressed and runs the discovered
 * {@link PublishInterceptor} chain once, and on {@code ACCEPT} the edge restreams the stored blob into the claiming
 * format, which lays out its own namespace with {@link #storeBlob}/{@link #link} (a {@code publish/} pointer) or a
 * {@code Blobs} write, then calls {@link #published} so the after-commit {@link PublicationObserver}s ride the accepted
 * publish. Screening a body inside a format (a second, format-embedded chain run over already-screened bytes) is not
 * this model; the {@code screen} + layout + {@code published} split is the one documented idiom.
 */
public final class Publication {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Publication.class);

    /** The ceiling on a {@link PublishInterceptor.Content#sibling} read. A sibling is small published metadata a gate
     *  inspects beside the artifact (a jar reading its POM, a package reading its manifest) - never a whole artifact -
     *  so the buffered read is capped at a few MiB. Past it the read fails loudly rather than materialising an
     *  arbitrarily large blob into the heap, which would let a screen be turned into an out-of-memory lever. */
    private static final int LARGEST_SIBLING = 8 * 1024 * 1024;

    /** The one discovered publication hook class, loaded once at class load like {@code MavenFormat.MODULE_VIEWS}:
     *  every {@link PublicationObserver} on the module path, the interceptors among them included - a
     *  {@link PublishInterceptor} IS a {@code PublicationObserver}, so a single {@code uses PublicationObserver}
     *  clause discovers both. Empty in the free edition (no provider on the module path). */
    private static final List<PublicationObserver> OBSERVERS = ServiceLoader.load(PublicationObserver.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    /** The verdict-bearing subset, split from the one discovered list by {@code instanceof PublishInterceptor}: the
     *  observers that also screen. So {@link #screen} drives exactly the interceptors while {@link #published} and
     *  {@link #unpublish} still notify every discovered observer - the interceptors ride the after-commit call too
     *  (their {@link PublishInterceptor#onPublished} defaults to a no-op, so this never double-counts a screen). */
    private static final List<PublishInterceptor> DISCOVERED = OBSERVERS.stream()
            .filter(observer -> observer instanceof PublishInterceptor)
            .map(observer -> (PublishInterceptor) observer)
            .toList();

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
        // Delegate the servable-vs-not discrimination to the one enumeration seam so serve and enumeration can never
        // disagree (located empty iff state != SERVABLE); the seam composes this same publication's interceptor chain
        // and the withheld/<hash> marker convention. This is a behaviour-preserving refactor of the former inline
        // "chain withheld -> pointer resolve -> blobs/<hash> exists" (with the one gain the seam brings: a hostile,
        // unresolvable request path now fails closed to empty rather than throwing an InvalidPathException out of a
        // serve), so a linked, present, non-withheld path still resolves to blobs/<hash> exactly as before.
        if (new ServableNames(store, this).state(requestPath) != ServableNames.State.SERVABLE) {
            return Optional.empty();
        }
        return blob(requestPath).map(hash -> "blobs/" + hash);
    }

    /** Whether any interceptor in this publication's chain withholds the request path from serving - the chain probe
     *  {@link #located} runs, factored out so {@link ServableNames} composes the caller's interceptor list rather than
     *  discovering a second one. A verdict-bearing {@code withheld} that fails closed by throwing propagates exactly as
     *  it does through {@link #located}. */
    boolean withheld(String requestPath) throws IOException {
        for (PublishInterceptor interceptor : interceptors) {
            if (interceptor.withheld(requestPath, store)) {
                return true;
            }
        }
        return false;
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
        // The one cheap check the publish hot path pays: a non-quarantine link is exactly the write below and nothing
        // more. A /quarantine<path> link is the pointer face of the withhold-change feed - a hold writer (the gate's
        // QUARANTINE branch, a retroactive KEV/license/reachability sweep) links a review pointer here - so a FRESH one
        // (prior read absent, not an overwrite) fires onWithheld after the write. Transition-only: the sweeps guard on
        // presence before re-linking, so their idempotent converge passes overwrite rather than freshly link and raise
        // no event.
        boolean quarantine = requestPath.startsWith("/quarantine");
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ArtifactStore.Versioned> prior = store.readVersioned("publish" + requestPath);
            Object token = prior.map(ArtifactStore.Versioned::token).orElse(null);
            if (store.writeVersioned("publish" + requestPath, hash.getBytes(StandardCharsets.UTF_8), token)) {
                String condemned = "gc/condemned/" + hash;
                if (store.exists(condemned)) {
                    store.delete(condemned);
                }
                if (quarantine && prior.isEmpty()) {
                    notifyWithheld(ArtifactDescriptor.at(null, requestPath.substring("/quarantine".length()))
                            .withBlob(hash, -1L));
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
        // The pointer face of the withhold-change feed's transition-OFF leg: removing a /quarantine<servedPath> review
        // pointer clears that hold, so fire onWithholdCleared with the served path (the /quarantine prefix stripped) and
        // the pointer's hash - IN ADDITION TO the onDeleted above, which for a quarantine path carries no coordinate the
        // coordinate-keyed observers act on. A non-quarantine unpublish pays only one startsWith.
        if (requestPath.startsWith("/quarantine")) {
            ArtifactDescriptor cleared = ArtifactDescriptor.at(null, requestPath.substring("/quarantine".length()));
            notifyWithholdCleared(hash(named) ? cleared.withBlob(named, -1L) : cleared);
        }
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
        // The withhold-change feed's transition-OFF pointer leg, exactly as the string variant: a removed
        // /quarantine<servedPath> pointer fires onWithholdCleared with the stripped served path and the pointer's hash.
        if (described.path() != null && described.path().startsWith("/quarantine")) {
            ArtifactDescriptor cleared = ArtifactDescriptor.at(null, described.path().substring("/quarantine".length()));
            notifyWithholdCleared(hash(named) ? cleared.withBlob(named, -1L) : cleared);
        }
    }

    /** Notify every observer of a serving-pointer removal this primitive did not perform - the seam a layout-aware
     *  eviction calls once per pointer it deletes in a format's <em>own</em> namespace (a blobs-namespace key
     *  outside {@code publish/}), so those removals are observed exactly like an {@link #unpublish}. Failures are
     *  logged and contained like every observer notification; nothing is read or deleted here - the caller already
     *  removed the pointer and describes it. */
    public void deleted(ArtifactDescriptor removed) {
        notifyDeleted(removed);
    }

    /** Notify every observer of an accepted artifact this primitive did not lay out - the seam an ingress edge calls
     *  once per artifact it has screened to {@code ACCEPT} and laid out into a format's namespace (through
     *  {@link #screen} then the format's own {@link #storeBlob}/{@link #link} or {@code Blobs} writes), so an
     *  edge-screened publish is observed exactly once the edge has linked the accepted artifact. The mirror of
     *  {@link #deleted}: the caller already stored and linked the artifact and describes it, so nothing is read or
     *  written here. Failures are logged and contained like every observer notification, never failing the caller's
     *  already-completed publish. This is the sole seam that carries {@link PublicationObserver#onPublished}: with the
     *  screen+layout choreography living at the ingress edges, a blobs-namespace deploy fires its observer through here. */
    public void published(ArtifactDescriptor published) {
        notifyPublished(published);
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
                LOGGER.warn("publication observer "
                        + observer.getClass().getName() + " failed for removal of " + removed.path(), exception);
            }
        }
    }

    private void notifyPublished(ArtifactDescriptor published) {
        for (PublicationObserver observer : observers) {
            try {
                observer.onPublished(published, store);
            } catch (Exception exception) {
                LOGGER.warn("publication observer "
                        + observer.getClass().getName() + " failed for " + published.path(), exception);
            }
        }
    }

    /** The withhold-change feed's transition-ON notify - the pointer face {@link #link} fires over this publication's
     *  observer list, contained exactly like {@link #notifyDeleted}. */
    private void notifyWithheld(ArtifactDescriptor subject) {
        for (PublicationObserver observer : observers) {
            try {
                observer.onWithheld(subject, store);
            } catch (Exception exception) {
                LOGGER.warn("publication observer "
                        + observer.getClass().getName() + " failed for withhold of " + subject.path(), exception);
            }
        }
    }

    /** The transition-OFF mirror {@link #unpublish} fires when a {@code /quarantine} review pointer is removed. */
    private void notifyWithholdCleared(ArtifactDescriptor subject) {
        for (PublicationObserver observer : observers) {
            try {
                observer.onWithholdCleared(subject, store);
            } catch (Exception exception) {
                LOGGER.warn("publication observer " + observer.getClass().getName()
                        + " failed for withhold-clear of " + subject.path(), exception);
            }
        }
    }

    /** The withhold-change feed's transition-ON notify over the ServiceLoader-discovered {@link #OBSERVERS} - the
     *  package-private static seam the same-package {@link Withheld#mark} (a static primitive with no {@code Publication}
     *  instance) fires the marker face through, reusing the one discovered observer list rather than a second discovery.
     *  Failures are logged and contained exactly as on the instance notify paths, so a hold's marker write never fails
     *  open because a downstream consumer is down. */
    static void notifyWithheld(ArtifactDescriptor subject, ArtifactStore store) {
        for (PublicationObserver observer : OBSERVERS) {
            try {
                observer.onWithheld(subject, store);
            } catch (Exception exception) {
                LOGGER.warn("publication observer " + observer.getClass().getName()
                        + " failed for withhold of hash " + subject.hash(), exception);
            }
        }
    }

    /** The transition-OFF mirror the same-package {@link Withheld#clear} fires through - the marker-cleared face. */
    static void notifyWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) {
        for (PublicationObserver observer : OBSERVERS) {
            try {
                observer.onWithholdCleared(subject, store);
            } catch (Exception exception) {
                LOGGER.warn("publication observer " + observer.getClass().getName()
                        + " failed for withhold-clear of hash " + subject.hash(), exception);
            }
        }
    }

    /** The outcome of a screened upload: the disposition the interceptor chain reached and the SHA-256 the blob was
     *  stored under - present whatever the disposition, since the blob is written content-addressed before the gate. */
    public record Published(PublishInterceptor.Disposition disposition, String hash) {
    }

    /**
     * Store an upload content-addressed and run the {@link PublishInterceptor} chain over its neutral
     * {@link ArtifactDescriptor} <em>without linking any serving pointer of its own</em>: an accepted upload links no
     * pointer - the caller owns the accepted write, laying the content out in its own layout from the returned hash -
     * while a quarantined one is still diverted to the {@code /quarantine} view for review and a rejected one leaves
     * only the unreferenced blob for garbage collection. The blob is inert until a pointer references it, so the chain
     * gates before any link - nothing is buffered and there is no published-then-retracted window. With the default
     * empty chain this is exactly a {@link #storeBlob} that always {@code ACCEPT}s.
     *
     * <p>This is the single sanctioned screen seam. An ingress edge (the deploy edge {@code ScreenedDispatch}, the
     * batch explode, the import walk, or OCI's manifest choke point) screens an upload here, then - on {@code ACCEPT} -
     * lays the stored body out in a format's namespace with {@link #storeBlob}/{@link #link} (or a {@code Blobs} write)
     * and fires {@link #published} so the after-commit {@link PublicationObserver}s ride the accepted publish. The
     * interceptors' {@link PublishInterceptor#committed} notifications fire here; the after-commit observers do not -
     * they ride the {@link #published} seam the edge calls once it has laid the accepted artifact out.
     */
    public Published screen(ArtifactDescriptor artifact, InputStream content) throws IOException {
        return route(artifact, content);
    }

    private Published route(ArtifactDescriptor artifact, InputStream content) throws IOException {
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
            // ACCEPT links no pointer of its own: the screening edge owns the accepted write and lays the stored blob
            // out in its own format namespace from the returned hash, then fires published() for the observers.
            case ACCEPT -> {
            }
            // QUARANTINE still diverts to the quarantine view (stored but not served) for review.
            case QUARANTINE -> link("/quarantine" + artifact.path(), hash);
            // REJECT links nothing; the orphaned blob is left for garbage collection.
            case REJECT -> {
            }
        }
        for (PublishInterceptor interceptor : interceptors) {
            interceptor.committed(stored, disposition, store);
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
