package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactDescriptor;

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

    /** The descriptor for a request path this format owns (hash and size unset, since nothing is stored yet), or empty
     *  when the path carries no coordinate to describe (generated metadata, a directory). Derived from the path only. */
    Optional<ArtifactDescriptor> describe(String path);

    /** The request-path directory prefixes a coordinate version occupies across this format's layouts, so a cleanup
     *  pass can enumerate and unpublish every pointer under them from the coordinate alone - no layout knowledge in the
     *  caller. Empty when the coordinate maps nowhere. */
    List<String> paths(String coordinate, String version);
}
