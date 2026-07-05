package build.jenesis.repository.store;

import module java.base;

/**
 * An after-commit observer of {@link Publication#publish} - the second hook class beside the verdict-bearing
 * {@link PublishInterceptor}. Discovered with {@link java.util.ServiceLoader} like the screens, but notified only once
 * an accepted artifact's pointer is linked and serving: a quarantined or rejected publish is never observed, and an
 * observer has no say in the disposition. This is the seam for what rides a publish without sitting in its verdict
 * path - forwarding to another repository, a webhook, replication, handing a deeper scan to a worker: an observer's
 * failure is logged and contained (it never unlinks the artifact or fails the upload), and anything slow belongs in a
 * background worker the observer only leaves a note for - record a small store object here, drain it elsewhere - so a
 * remote target's latency or outage never couples into the local publish.
 */
public interface PublicationObserver {

    /** React to a committed publish: the linked {@link ArtifactDescriptor} (content-addressed hash and size set) and
     *  the same scoped store it was published through, so a recorded follow-up (an outbox entry, a replication
     *  marker) lands under exactly the space the artifact did. */
    void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException;
}
