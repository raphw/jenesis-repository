package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * A test-only publication screen, discovered like a real one, that lets the OCI choke-point test exercise the whole
 * verdict range without a compliance module on the path - the OCI mirror of the server suite's {@code MarkerInterceptor}.
 * It is inert for every ordinary coordinate ({@code ACCEPT}, so every existing OCI test pushes, serves and imports
 * exactly as before) and reacts only to two distinctive markers a test puts in an image name: a coordinate containing
 * {@code gate-reject} is {@code REJECT}ed, one containing {@code gate-quarantine} is {@code QUARANTINE}d. Because only
 * a manifest write runs {@link build.jenesis.repository.store.Publication#screen} (a layer blob upload never does), this
 * proves a screened-out manifest is withheld from serving while its layer blobs stay served raw.
 */
public final class OciScreenInterceptor implements PublishInterceptor {

    static final String QUARANTINE_MARKER = "gate-quarantine";
    static final String REJECT_MARKER = "gate-reject";

    @Override
    public Disposition assess(ArtifactDescriptor artifact, Content content) {
        String path = artifact.path();
        if (path == null) {
            return Disposition.ACCEPT;
        }
        if (path.contains(REJECT_MARKER)) {
            return Disposition.REJECT;
        }
        if (path.contains(QUARANTINE_MARKER)) {
            return Disposition.QUARANTINE;
        }
        return Disposition.ACCEPT;
    }
}
