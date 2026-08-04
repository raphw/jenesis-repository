package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

import module java.base;

/**
 * A ServiceLoader-discovered {@link PublicationObserver} that records the withhold-change feed's transition events
 * ({@link #onWithheld} / {@link #onWithholdCleared}) into static lists, so a test can assert the two durable withhold
 * choke points that fire through the discovered {@code Publication.OBSERVERS} list - {@code Withheld.mark} and
 * {@code Withheld.clear}, which are static and carry no injectable observer list - actually raise the feed signal, and
 * exactly once per transition. The {@link #fail} toggle makes the observer throw so a test can prove a contained
 * consumer failure never fails the mark. Reset the static state per test with {@link #reset()}.
 *
 * <p>It is a base-only observer (never a {@code PublishInterceptor}), so registering it adds nothing to the discovered
 * verdict chain; its {@link #onPublished} is an inert no-op and {@code onDeleted} keeps the default, so it perturbs no
 * other store-SPI test that publishes or unpublishes through the default {@code Publication}.
 */
public final class RecordingWithholdObserver implements PublicationObserver {

    static final List<ArtifactDescriptor> WITHHELD = new CopyOnWriteArrayList<>();
    static final List<ArtifactDescriptor> CLEARED = new CopyOnWriteArrayList<>();
    static volatile boolean fail;

    static void reset() {
        WITHHELD.clear();
        CLEARED.clear();
        fail = false;
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
    }

    @Override
    public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (fail) {
            throw new IOException("withhold-feed consumer down");
        }
        WITHHELD.add(subject);
    }

    @Override
    public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        if (fail) {
            throw new IOException("withhold-feed consumer down");
        }
        CLEARED.add(subject);
    }
}
