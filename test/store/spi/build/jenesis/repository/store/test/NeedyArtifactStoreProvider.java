package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import java.util.Set;
import java.util.function.UnaryOperator;

/** A backend that declares required configuration it never receives, so the fail-loud resolve path is testable. */
public final class NeedyArtifactStoreProvider implements ArtifactStoreProvider {

    @Override
    public String name() {
        return "needy";
    }

    @Override
    public Set<String> requiredConfig() {
        return Set.of("NEEDY_BUCKET");
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        throw new AssertionError("The needy backend is only ever resolved without its required config");
    }
}
