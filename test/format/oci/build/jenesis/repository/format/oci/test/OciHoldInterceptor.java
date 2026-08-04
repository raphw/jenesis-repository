package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

import module java.base;

/**
 * A test-only, ServiceLoader-discovered screen that stands a <em>retroactive</em> withhold's review pointer on any
 * request path carrying the distinctive {@code retro-held} segment - it overrides only {@link #withheld} (the
 * quarantine read side an enterprise {@code ComplianceScreen} implements), returning {@code true} for such a path, and
 * leaves {@code assess} at its {@code ACCEPT} default. That lets the OCI choke-point test prove the guarded ACCEPT-clear
 * (§6 Q-D): a re-pushed, now-accepted identical manifest must NOT tear down a standing retroactive hold on the same
 * bytes. It is inert for every ordinary coordinate (no {@code retro-held} segment), so it perturbs no other OCI test.
 */
public final class OciHoldInterceptor implements PublishInterceptor {

    static final String HOLD_MARKER = "retro-held";

    @Override
    public boolean withheld(String path, ArtifactStore store) {
        return path != null && path.contains(HOLD_MARKER);
    }
}
