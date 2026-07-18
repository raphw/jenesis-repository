package build.jenesis.repository.format.raw.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * A test-only publication screen, discovered like a real one through this test module's {@code provides}, so a test can
 * exercise the whole verdict range without a compliance module on the path. It is inert for every ordinary path -
 * {@code ACCEPT}, so the rest of the raw suite publishes and serves exactly as before - and reacts only to two marker
 * tokens a test puts in the path: one containing {@code gate-quarantine} is quarantined, one containing
 * {@code gate-reject} is rejected. This is what lets a format-level test assert that {@code RawFormat}'s PUT / proxy /
 * import route through the gate (a {@code QUARANTINE} withholds serving, a {@code REJECT} links nothing) rather than the
 * old raw store-then-link, which would admit every path unscreened. Mirrors {@code test/server}'s marker interceptor.
 */
public final class MarkerInterceptor implements PublishInterceptor {

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
