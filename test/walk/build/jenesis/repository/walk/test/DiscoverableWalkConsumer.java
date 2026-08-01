package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * A {@link WalkConsumer} registered as a {@link java.util.ServiceLoader} service in this test module (see the
 * module's {@code provides} directive), so {@link WalkConsumer#discovered()} can be exercised: it is enumerated when
 * enabled and skipped when {@code jenesis.repository.discoverable-test-consumer=false}. It does no work - the
 * discovery and the {@code Features} feature-gate are what is under test, not any derived state.
 */
public final class DiscoverableWalkConsumer implements WalkConsumer {

    /** A distinctive name so its {@code jenesis.repository.<name>} feature toggle collides with nothing real. */
    public static final String NAME = "discoverable-test-consumer";

    public DiscoverableWalkConsumer() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
    }
}
