package build.jenesis.repository.store;

import module java.base;

/**
 * A post-processing hook run when an artifact upload is committed, format-neutrally. Discovered with
 * {@link java.util.ServiceLoader} - like the formats, the storage backends and the module-view bridge - and run by
 * {@link Publication#publish}: once the blob is stored content-addressed but before its pointer is linked, every
 * interceptor {@link #assess assesses} the neutral {@link ArtifactDescriptor}; the publication is routed by the
 * strongest {@link Disposition} across the chain, then each interceptor is {@link #committed notified} of the outcome.
 * By default no provider ships, so the chain is empty and every upload is accepted and linked exactly as before;
 * a deployment can plug a compliance gate, quarantine audit or inventory recording in here - with no
 * format-specific logic, since the coordinate arrives on the descriptor.
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

    /** Decide this artifact's disposition before its pointer is linked; {@code ACCEPT} by default. */
    default Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
        return Disposition.ACCEPT;
    }

    /** React to the routed outcome once the collective disposition is decided - the seam for inventory recording on
     *  {@code ACCEPT}, a quarantine or rejection audit otherwise, or handing a deeper scan to a background worker. */
    default void committed(ArtifactDescriptor artifact, Disposition disposition) throws IOException {
    }
}
