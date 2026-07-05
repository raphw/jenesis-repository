package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The after-commit hook class on {@link Publication#publish}: a {@link PublicationObserver} is notified only once an
 * accepted artifact's pointer is linked - never for a quarantined or rejected publish - and rides outside the verdict
 * path, so a failing observer is contained and the publish it observed stays linked and served. Observers are passed
 * explicitly here, since the free edition's ServiceLoader-discovered chain is empty.
 */
class PublicationObserverTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /** A screen answering a fixed verdict, to drive the quarantine and reject outcomes past the observers. */
    private static PublishInterceptor verdict(PublishInterceptor.Disposition disposition) {
        return new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) {
                return disposition;
            }
        };
    }

    @Test
    void an_observer_sees_only_a_committed_publish() throws IOException {
        List<ArtifactDescriptor> observed = new ArrayList<>();
        PublicationObserver observer = (artifact, store) -> observed.add(artifact);

        new Publication(store, List.of(verdict(PublishInterceptor.Disposition.QUARANTINE)), List.of(observer))
                .publish(ArtifactDescriptor.at("raw", "/raw/held"), bytes("suspect"));
        new Publication(store, List.of(verdict(PublishInterceptor.Disposition.REJECT)), List.of(observer))
                .publish(ArtifactDescriptor.at("raw", "/raw/bad"), bytes("malware"));
        assertThat(observed).as("a quarantined or rejected publish is never observed").isEmpty();

        Publication.Published published = new Publication(store, List.of(), List.of(observer))
                .publish(ArtifactDescriptor.at("raw", "/raw/good"), bytes("payload"));
        assertThat(observed).hasSize(1);
        assertThat(observed.getFirst().path()).isEqualTo("/raw/good");
        assertThat(observed.getFirst().hash()).as("the descriptor carries the linked blob identity")
                .isEqualTo(published.hash());
        assertThat(observed.getFirst().size()).isEqualTo("payload".length());
    }

    @Test
    void a_failing_observer_is_contained_and_the_publish_stays_linked() throws IOException {
        List<String> reached = new ArrayList<>();
        Publication publication = new Publication(store, List.of(), List.of(
                (artifact, store) -> {
                    throw new IOException("remote target down");
                },
                (artifact, store) -> reached.add(artifact.path())));

        Publication.Published published = publication.publish(ArtifactDescriptor.at("raw", "/raw/a"), bytes("x"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(publication.located("/raw/a")).as("the failure never unlinks the artifact")
                .contains("blobs/" + published.hash());
        assertThat(reached).as("later observers still run").containsExactly("/raw/a");
    }

    @Test
    void an_observer_records_through_the_published_scope() throws IOException {
        ArtifactStore scoped = store.scope("acme").scope("main");
        PublicationObserver outbox = (artifact, target) ->
                target.writeVersioned("outbox/" + artifact.hash(), artifact.path().getBytes(StandardCharsets.UTF_8), null);

        new Publication(scoped, List.of(), List.of(outbox))
                .publish(ArtifactDescriptor.at("raw", "/raw/forward-me"), bytes("payload"));

        List<String> entries = scoped.list("outbox");
        assertThat(entries).as("the follow-up note lands under the space the artifact did").hasSize(1);
        assertThat(scoped.readVersioned("outbox/" + entries.getFirst()))
                .hasValueSatisfying(versioned -> assertThat(new String(versioned.content(), StandardCharsets.UTF_8))
                        .isEqualTo("/raw/forward-me"));
    }
}
