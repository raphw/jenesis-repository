package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

import java.util.List;
import java.util.Optional;

/**
 * The optional coordinate/layout capability of a {@link RepositoryFormat}: it maps a request path this format owns to
 * its neutral {@link ArtifactDescriptor} and back, from the path alone - no content read - so read-side concerns
 * (download tracking) and coordinate-only concerns (cleanup eviction) map path to coordinate and back without
 * hand-parsing a layout. Kept off the {@link RepositoryFormat} contract, exactly like {@link ProxyFormat}, so a
 * hosted-only format (raw) that has no coordinates is not forced to implement it; neutral code detects the capability
 * with {@code instanceof}. A format is the single owner of its layout knowledge (the coordinate convention, the
 * prerelease rule, the directory a version occupies), and this is the interface through which it lends that knowledge
 * to the rest of the system.
 */
public interface ArtifactLayout {

    /** The package-ecosystem name this format's artifacts report - the value {@link #describe}'s descriptors carry
     *  (a Maven format's OSV name {@code "Maven"}, an npm format's {@code "npm"}) - so a coordinate-only consumer, a
     *  cleanup eviction resolving a stored coordinate back to its format, finds the format by its declared ecosystem
     *  rather than guessing from the format id. */
    String ecosystem();

    /** The descriptor for a request path this format owns (hash and size unset, since nothing is stored yet), or empty
     *  when the path carries no coordinate to describe (generated metadata, a directory). Derived from the path only. */
    Optional<ArtifactDescriptor> describe(String path);

    /** The request-path directory prefixes a coordinate version occupies across this format's layouts, resolved
     *  against {@code store} so a format can include a cross-published mirror it recorded (a Maven module view found
     *  through the format's own index), so a cleanup pass enumerates and unpublishes every pointer under them from the
     *  coordinate alone - no layout knowledge in the caller. Empty when the coordinate maps nowhere. */
    List<String> paths(String coordinate, String version, ArtifactStore store);

    /** The request-path folders a coordinate version occupies computed from the coordinate alone - no artifact read,
     *  the primary layout path first. A <em>read path</em> that only needs to navigate to a coordinate's folder (a
     *  console search linking a hit into the browse tree) calls this, never {@link #paths(String, String, ArtifactStore)}
     *  whose store-derived cross-published mirrors may open a stored artifact (Maven reads a jar's module name for its
     *  {@code /module/} mirror) - so the read path never buffers a blob. Defaults to empty, which is exact for a format
     *  whose pointers are not enumerable from the coordinate alone (the shared-{@code blobs} formats already return
     *  empty from the store overload); a format overrides it when its primary folder is a pure function of the
     *  coordinate. */
    default List<String> paths(String coordinate, String version) {
        return List.of();
    }
}
