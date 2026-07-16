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
    void an_unpublish_notifies_once_per_removed_pointer_with_path_and_hash() throws IOException {
        List<ArtifactDescriptor> removed = new ArrayList<>();
        PublicationObserver observer = new PublicationObserver() {
            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
            }

            @Override
            public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) {
                removed.add(artifact);
            }
        };
        Publication publication = new Publication(store, List.of(), List.of(observer));
        Publication.Published published = publication.publish(ArtifactDescriptor.at("raw", "/raw/a"), bytes("x"));
        publication.publish(ArtifactDescriptor.at("raw", "/raw/b"), bytes("y"));

        publication.unpublish("/raw/a");
        publication.unpublish("/raw/never-published");

        assertThat(removed).as("once per removed pointer - an absent path removes nothing and notifies nothing")
                .hasSize(1);
        assertThat(removed.getFirst().path()).isEqualTo("/raw/a");
        assertThat(removed.getFirst().hash()).as("the site's descriptor richness: the hash the pointer named")
                .isEqualTo(published.hash());
        assertThat(removed.getFirst().coordinate()).as("the free store knows no layouts").isNull();
        assertThat(publication.blob("/raw/b")).as("the sibling pointer is untouched").isPresent();
    }

    @Test
    void a_layout_aware_eviction_enriches_the_removal_it_describes() throws IOException {
        List<ArtifactDescriptor> removed = new ArrayList<>();
        PublicationObserver observer = new PublicationObserver() {
            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
            }

            @Override
            public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) {
                removed.add(artifact);
            }
        };
        Publication publication = new Publication(store, List.of(), List.of(observer));
        Publication.Published published = publication
                .publish(ArtifactDescriptor.at("maven", "/com/acme/app/1.0/app-1.0.jar"), bytes("jar bytes"));

        publication.unpublish(new ArtifactDescriptor("maven", "com.acme:app", "1.0",
                "/com/acme/app/1.0/app-1.0.jar", null, false, null, -1L));
        publication.deleted(new ArtifactDescriptor("npm", "lodash", "4.17.21",
                "npm/lodash/-/lodash-4.17.21.tgz", null, false, "0".repeat(64), -1L));

        assertThat(removed).hasSize(2);
        assertThat(removed.getFirst().coordinate()).as("the eviction's layout knowledge rides the event")
                .isEqualTo("com.acme:app");
        assertThat(removed.getFirst().hash()).as("the blob identity is completed from the pointer")
                .isEqualTo(published.hash());
        assertThat(publication.blob("/com/acme/app/1.0/app-1.0.jar")).isEmpty();
        assertThat(removed.getLast().path()).as("a blobs-namespace removal is observed exactly like an unpublish")
                .isEqualTo("npm/lodash/-/lodash-4.17.21.tgz");
    }

    @Test
    void a_publish_only_observer_rides_a_removal_through_the_default_no_op() throws IOException {
        List<ArtifactDescriptor> observed = new ArrayList<>();
        Publication publication = new Publication(store, List.of(), List.of((artifact, store) -> observed.add(artifact)));
        publication.publish(ArtifactDescriptor.at("raw", "/raw/a"), bytes("x"));

        publication.unpublish("/raw/a");

        assertThat(observed).as("onDeleted defaults to a no-op, so an existing observer is untouched").hasSize(1);
        assertThat(publication.blob("/raw/a")).isEmpty();
    }

    @Test
    void a_failing_removal_observer_is_contained_and_the_pointer_stays_removed() throws IOException {
        List<String> reached = new ArrayList<>();
        PublicationObserver failing = new PublicationObserver() {
            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
            }

            @Override
            public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                throw new IOException("webhook target down");
            }
        };
        PublicationObserver recording = new PublicationObserver() {
            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
            }

            @Override
            public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) {
                reached.add(artifact.path());
            }
        };
        Publication publication = new Publication(store, List.of(), List.of(failing, recording));
        publication.publish(ArtifactDescriptor.at("raw", "/raw/a"), bytes("x"));

        publication.unpublish("/raw/a");

        assertThat(publication.blob("/raw/a")).as("the failure never blocks the removal").isEmpty();
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
