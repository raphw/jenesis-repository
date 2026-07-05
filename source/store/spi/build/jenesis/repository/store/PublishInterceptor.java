package build.jenesis.repository.store;

import module java.base;

/**
 * The verdict-bearing screen over an artifact publication, format-neutral. Discovered with
 * {@link java.util.ServiceLoader} - like the formats, the storage backends and the module-view bridge - and run as an
 * {@link #order() ordered} chain by {@link Publication#publish}: once the blob is stored content-addressed but before
 * its pointer is linked, every screen {@link #assess assesses} the neutral {@link ArtifactDescriptor}; the publication
 * is routed by the strongest {@link Disposition} across the chain, then each screen is {@link #committed notified} of
 * the outcome. The screen also holds the quarantine read side: {@link Publication#located} asks the chain whether a
 * published path is {@link #withheld}, so a verdict that changes after the fact retracts an already-linked artifact
 * from serving. By default no provider ships, so the chain is empty and every upload is accepted, linked and served
 * exactly as before; a deployment can plug a compliance gate, quarantine audit or inventory recording in here - with
 * no format-specific logic, since the coordinate arrives on the descriptor. A hook with no say in the verdict - a
 * forwarder, a webhook - is the other hook class, the after-commit {@link PublicationObserver}.
 */
public interface PublishInterceptor {

    /** What to do with a just-stored artifact, ordered weakest-to-strongest so a chain keeps the strongest verdict:
     *  {@code ACCEPT} links it as published, {@code QUARANTINE} diverts its pointer to a quarantine view (stored, not
     *  served), {@code REJECT} links nothing (the orphaned blob is reclaimed by the usual garbage collection). */
    enum Disposition {
        ACCEPT,
        QUARANTINE,
        REJECT
    }

    /** Read access to the just-stored blob and its already-published siblings, so a gate can inspect the artifact -
     *  read a jar, or the sibling POM beside it - without reaching into the storage layer or buffering the upload. */
    interface Content {

        /** Open the blob this artifact was stored under ({@code blobs/<hash>}); the caller closes the stream. */
        InputStream open() throws IOException;

        /** The bytes already published at a sibling request path (a jar reading its POM), or empty if nothing is there. */
        Optional<byte[]> sibling(String path) throws IOException;
    }

    /** The screen's position in the chain, lower first; screens sharing a position keep their discovery order. The
     *  collective disposition is order-independent (the strongest verdict wins) - ordering matters to a screen that
     *  reads what an earlier one recorded, and to the {@link #committed} notification sequence. */
    default int order() {
        return 0;
    }

    /** Decide this artifact's disposition before its pointer is linked; {@code ACCEPT} by default. */
    default Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
        return Disposition.ACCEPT;
    }

    /** Whether the artifact published at this request path is currently withheld from serving - the quarantine read
     *  side: a screen that diverts a fresh upload can also retract an already-linked path when its verdict changes
     *  after the fact (a new advisory against an artifact that has served for months). Consulted by
     *  {@link Publication#located} against the same scoped store the publication serves from, on every read - so an
     *  implementation keeps it cheap. Serves ({@code false}) by default. */
    default boolean withheld(String path, ArtifactStore store) throws IOException {
        return false;
    }

    /** React to the routed outcome once the collective disposition is decided - the seam for inventory recording on
     *  {@code ACCEPT}, a quarantine or rejection audit otherwise. A hook that only rides an accepted publish and has
     *  no say in the verdict belongs in the other hook class, the after-commit {@link PublicationObserver}. */
    default void committed(ArtifactDescriptor artifact, Disposition disposition) throws IOException {
    }
}
