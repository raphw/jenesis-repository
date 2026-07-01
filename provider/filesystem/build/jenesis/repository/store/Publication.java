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

    private final ArtifactStore store;

    public Publication(ArtifactStore store) {
        this.store = store;
    }

    /** The blob key ({@code blobs/<hash>}) a path resolves to when it is published and the blob is present - what a
     *  streaming {@code GET} sets its {@code Content-Length} from (through {@link ArtifactStore#size}) and then copies
     *  to the response (through {@link ArtifactStore#read}), instead of buffering the blob to learn its length. Empty
     *  when nothing is published there or the blob is gone. */
    public Optional<String> located(String requestPath) throws IOException {
        return blob(requestPath).map(hash -> "blobs/" + hash).filter(store::exists);
    }

    /** Stream content once, content-addressed while it is read, and return its hash - so a large artifact goes from the
     *  network to storage without being buffered whole in memory. The primitive a staging deploy or a cross-publish
     *  uses to hold bytes before any view points at them. */
    public String storeBlob(InputStream content) throws IOException {
        return store.writeBlob(content);
    }

    /** Point a request path at an already-stored blob - the primitive promotion and cross-publishing use to publish a
     *  blob under another view without re-uploading it. */
    public void link(String requestPath, String hash) throws IOException {
        Object token = store.readVersioned("publish" + requestPath).map(ArtifactStore.Versioned::token).orElse(null);
        store.writeVersioned("publish" + requestPath, hash.getBytes(StandardCharsets.UTF_8), token);
    }

    /** The content hash a path currently points at, or empty if nothing is published there. */
    public Optional<String> blob(String requestPath) throws IOException {
        return store.readVersioned("publish" + requestPath)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim());
    }

    /** Remove a single published pointer; the blob it referenced is left for a later garbage collection, since
     *  another pointer may still reference it. */
    public void unpublish(String requestPath) throws IOException {
        if (store.readVersioned("publish" + requestPath).isPresent()) {
            store.delete("publish" + requestPath);
        }
    }
}
