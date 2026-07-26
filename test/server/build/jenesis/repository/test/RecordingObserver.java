package build.jenesis.repository.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test-only after-commit observer, discovered like a real one, that records the {@link ArtifactDescriptor} of every
 * accepted publish carrying the distinctive {@code publish-observed} token in its path - inert for every other path,
 * so the rest of the server suite publishes exactly as before. It exists so the import-edge test can prove that an
 * accepted import fires {@link build.jenesis.repository.store.Publication#published} (an observer sees the accepted
 * artifact's identity), the observable half of the edge's screen/layout/observe choreography.
 */
public final class RecordingObserver implements PublicationObserver {

    static final String MARKER = "publish-observed";

    private static final List<ArtifactDescriptor> PUBLISHED = new CopyOnWriteArrayList<>();

    static void reset() {
        PUBLISHED.clear();
    }

    static List<ArtifactDescriptor> published() {
        return List.copyOf(PUBLISHED);
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        if (artifact.path() != null && artifact.path().contains(MARKER)) {
            PUBLISHED.add(artifact);
        }
    }
}
