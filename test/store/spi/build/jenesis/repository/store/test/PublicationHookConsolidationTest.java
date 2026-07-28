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

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WSPI.2 (b): the two publication hooks collapse to one discovered seam - {@link PublishInterceptor} now
 * {@code extends PublicationObserver}, so a single {@code uses PublicationObserver} clause discovers both and
 * {@link Publication} splits the one discovered list by {@code instanceof PublishInterceptor}. This pins the
 * consolidation invariants the discovered path relies on: a base-only observer still observes and never screens; an
 * interceptor both intercepts and observes; the {@code instanceof} split selects exactly the interceptors while every
 * hook stays an observer; and the per-method failure semantics survive the merge (verdict methods propagate, the
 * inherited observer methods are contained). Hooks are passed explicitly here, since the free edition's discovered
 * chain is empty.
 */
class PublicationHookConsolidationTest {

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

    /** The ingress-edge choreography the observers ride: screen, and only on ACCEPT link and fire the published seam. */
    private static Publication.Published edgePublish(Publication publication, ArtifactStore store,
                                                     ArtifactDescriptor descriptor, InputStream content)
            throws IOException {
        Publication.Published screened = publication.screen(descriptor, content);
        if (screened.disposition() == PublishInterceptor.Disposition.ACCEPT) {
            publication.link(descriptor.path(), screened.hash());
            publication.published(descriptor.withBlob(screened.hash(), store.size("blobs/" + screened.hash())));
        }
        return screened;
    }

    @Test
    void an_interceptor_is_a_publication_observer_and_the_instanceof_split_selects_it() {
        PublicationObserver baseObserver = (artifact, store) -> {
        };
        PublishInterceptor interceptor = new PublishInterceptor() {
        };

        assertThat(interceptor).as("a verdict-bearing screen IS an observer").isInstanceOf(PublicationObserver.class);
        assertThat(baseObserver).as("a base observer is never a screen").isNotInstanceOf(PublishInterceptor.class);

        // Exactly the expression Publication uses to split its one discovered list: every hook is an observer, and
        // filtering by instanceof selects only the interceptor into the verdict chain.
        List<PublicationObserver> discovered = List.of(baseObserver, interceptor);
        List<PublishInterceptor> chain = discovered.stream()
                .filter(hook -> hook instanceof PublishInterceptor)
                .map(hook -> (PublishInterceptor) hook)
                .toList();
        assertThat(chain).as("the instanceof split routes only the interceptor into the chain")
                .containsExactly(interceptor);
    }

    @Test
    void a_base_only_observer_still_observes_and_never_screens() throws IOException {
        List<String> observed = new ArrayList<>();
        PublicationObserver observer = (artifact, store) -> observed.add(artifact.path());

        // A base-only observer sits in the observer list, never the interceptor chain - so the empty chain accepts and
        // the observer rides the accepted publish exactly as before the merge.
        edgePublish(new Publication(store, List.of(), List.of(observer)),
                store, ArtifactDescriptor.at("raw", "/raw/observed"), bytes("payload"));

        assertThat(observed).as("a base observer observes an accepted publish").containsExactly("/raw/observed");
        assertThat(observer).isNotInstanceOf(PublishInterceptor.class);
    }

    @Test
    void an_interceptor_both_intercepts_and_observes() throws IOException {
        List<String> assessed = new ArrayList<>();
        List<PublishInterceptor.Disposition> committed = new ArrayList<>();
        List<String> observed = new ArrayList<>();
        // A screen that also opts into the observer role by overriding the inherited onPublished. The discovered path
        // places such a hook in BOTH the interceptor chain and the observer list (one object, one instanceof); driving
        // it in both here mirrors that split.
        PublishInterceptor hook = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) {
                assessed.add(artifact.path());
                return Disposition.ACCEPT;
            }

            @Override
            public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store) {
                committed.add(disposition);
            }

            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
                observed.add(artifact.path());
            }
        };

        edgePublish(new Publication(store, List.of(hook), List.of(hook)),
                store, ArtifactDescriptor.at("raw", "/raw/both"), bytes("payload"));

        assertThat(assessed).as("it intercepts (assess ran on the verdict path)").containsExactly("/raw/both");
        assertThat(committed).as("it is notified of the routed outcome").containsExactly(PublishInterceptor.Disposition.ACCEPT);
        assertThat(observed).as("and it also observes the accepted publish").containsExactly("/raw/both");
    }

    @Test
    void an_interceptors_inherited_onPublished_defaults_to_a_no_op() throws IOException {
        // A plain interceptor (no onPublished override) rides the observer list without double-counting the screen: its
        // inherited onPublished is a no-op, so being in both the chain and the observer list is harmless.
        List<PublishInterceptor.Disposition> committed = new ArrayList<>();
        PublishInterceptor plain = new PublishInterceptor() {
            @Override
            public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store) {
                committed.add(disposition);
            }
        };

        Publication.Published screened = edgePublish(new Publication(store, List.of(plain), List.of(plain)),
                store, ArtifactDescriptor.at("raw", "/raw/plain"), bytes("payload"));

        assertThat(screened.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(committed).as("the screen's own post-route hook is committed, not onPublished")
                .containsExactly(PublishInterceptor.Disposition.ACCEPT);
        assertThat(store.exists("blobs/" + screened.hash())).isTrue();
    }

    @Test
    void a_throwing_verdict_method_propagates_while_a_throwing_observe_method_is_contained() throws IOException {
        // Verdict path: an interceptor whose assess throws fails the write - a gate that cannot render a verdict must
        // not let an unscreened artifact through.
        PublishInterceptor failingAssess = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                throw new IOException("gate unavailable");
            }
        };
        assertThatThrownBy(() -> new Publication(store, List.of(failingAssess))
                .screen(ArtifactDescriptor.at("raw", "/raw/a"), bytes("x")))
                .as("a failing verdict method propagates").isInstanceOf(IOException.class);

        // Verdict path: committed throwing also propagates out of screen.
        PublishInterceptor failingCommitted = new PublishInterceptor() {
            @Override
            public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
                    throws IOException {
                throw new IOException("record failed");
            }
        };
        assertThatThrownBy(() -> new Publication(store, List.of(failingCommitted))
                .screen(ArtifactDescriptor.at("raw", "/raw/b"), bytes("x")))
                .as("committed propagates too").isInstanceOf(IOException.class);

        // Observe path: the SAME hook class's inherited onPublished is contained - a screen that also observes and
        // throws from onPublished never fails the already-committed publish, and later observers still run.
        List<String> reached = new ArrayList<>();
        PublishInterceptor observingScreen = new PublishInterceptor() {
            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                throw new IOException("webhook down");
            }
        };
        PublicationObserver recording = (artifact, store) -> reached.add(artifact.path());
        Publication publication = new Publication(store, List.of(), List.of(observingScreen, recording));
        Publication.Published published = edgePublish(publication, store,
                ArtifactDescriptor.at("raw", "/raw/c"), bytes("x"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(publication.located("/raw/c")).as("the contained observe failure never unlinks the publish")
                .contains("blobs/" + published.hash());
        assertThat(reached).as("later observers still run after a contained failure").containsExactly("/raw/c");
    }
}
