package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upload post-processing gate on {@link Publication#publish}: the blob is always stored content-addressed, then the
 * interceptor chain's strongest {@link PublishInterceptor.Disposition} routes the pointer - an accepted path links and
 * serves, a quarantined one diverts to the {@code /quarantine} view (stored but not served), a rejected one links
 * nothing - and every interceptor is notified of the outcome. Interceptors are passed explicitly here, since the free
 * edition's ServiceLoader-discovered chain is empty.
 */
class PublishInterceptorTest {

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

    private static ArtifactDescriptor descriptor(String path) {
        return ArtifactDescriptor.at("raw", path);
    }

    /** An interceptor that returns a fixed verdict and remembers the outcome it was told about. */
    private static final class Fixed implements PublishInterceptor {

        private final Disposition verdict;
        private Disposition committed;

        Fixed(Disposition verdict) {
            this.verdict = verdict;
        }

        @Override
        public Disposition assess(ArtifactDescriptor artifact, Content content) {
            return verdict;
        }

        @Override
        public void committed(ArtifactDescriptor artifact, Disposition disposition) {
            this.committed = disposition;
        }
    }

    @Test
    void an_empty_chain_accepts_and_links_the_artifact() throws IOException {
        Publication publication = new Publication(store, List.of());
        Publication.Published published = publication.publish(descriptor("/raw/a"), bytes("payload"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(store.exists("blobs/" + published.hash())).isTrue();
        assertThat(publication.located("/raw/a")).contains("blobs/" + published.hash());
    }

    @Test
    void a_quarantine_verdict_stores_the_blob_but_diverts_the_pointer() throws IOException {
        Fixed interceptor = new Fixed(PublishInterceptor.Disposition.QUARANTINE);
        Publication publication = new Publication(store, List.of(interceptor));

        Publication.Published published = publication.publish(descriptor("/raw/held"), bytes("suspect"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(store.exists("blobs/" + published.hash())).as("the blob is stored").isTrue();
        assertThat(publication.located("/raw/held")).as("but not served at its path").isEmpty();
        assertThat(publication.located("/quarantine/raw/held")).as("only under the quarantine view")
                .contains("blobs/" + published.hash());
        assertThat(interceptor.committed).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
    }

    @Test
    void a_reject_verdict_stores_the_blob_but_links_no_pointer() throws IOException {
        Publication publication = new Publication(store, List.of(new Fixed(PublishInterceptor.Disposition.REJECT)));

        Publication.Published published = publication.publish(descriptor("/raw/bad"), bytes("malware"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.REJECT);
        assertThat(store.exists("blobs/" + published.hash())).as("the orphan blob is left for GC").isTrue();
        assertThat(publication.located("/raw/bad")).isEmpty();
        assertThat(publication.located("/quarantine/raw/bad")).isEmpty();
    }

    @Test
    void the_strongest_disposition_across_the_chain_wins() throws IOException {
        Publication publication = new Publication(store, List.of(
                new Fixed(PublishInterceptor.Disposition.ACCEPT),
                new Fixed(PublishInterceptor.Disposition.QUARANTINE),
                new Fixed(PublishInterceptor.Disposition.ACCEPT)));

        Publication.Published published = publication.publish(descriptor("/raw/mixed"), bytes("x"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
    }

    @Test
    void screens_run_sorted_by_their_declared_order() throws IOException {
        List<String> sequence = new ArrayList<>();
        class Positioned implements PublishInterceptor {
            private final String name;
            private final int order;

            Positioned(String name, int order) {
                this.name = name;
                this.order = order;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) {
                sequence.add(name);
                return Disposition.ACCEPT;
            }
        }
        Publication publication = new Publication(store, List.of(
                new Positioned("last", 10), new Positioned("first", -10), new Positioned("middle", 0)));

        publication.publish(descriptor("/raw/ordered"), bytes("x"));

        assertThat(sequence).containsExactly("first", "middle", "last");
    }

    @Test
    void a_withholding_screen_retracts_a_published_path_from_serving() throws IOException {
        Publication publication = new Publication(store, List.of());
        publication.publish(descriptor("/raw/served"), bytes("fine"));
        publication.publish(descriptor("/raw/retracted"), bytes("later-flagged"));

        Publication screened = new Publication(store, List.of(new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                return path.equals("/raw/retracted");
            }
        }));

        assertThat(screened.located("/raw/served")).as("an unflagged path still serves").isPresent();
        assertThat(screened.located("/raw/retracted")).as("the flagged path is withheld").isEmpty();
        assertThat(screened.blob("/raw/retracted")).as("but its pointer is untouched").isPresent();
    }

    @Test
    void the_content_view_reads_the_just_stored_blob_and_a_published_sibling() throws IOException {
        new Publication(store, List.of()).publish(descriptor("/raw/pom"), bytes("the-pom"));

        List<String> seenSibling = new ArrayList<>();
        PublishInterceptor reader = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                try (InputStream in = content.open()) {
                    assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("the-jar");
                }
                content.sibling("/raw/pom").ifPresent(b -> seenSibling.add(new String(b, StandardCharsets.UTF_8)));
                return Disposition.ACCEPT;
            }
        };
        new Publication(store, List.of(reader)).publish(descriptor("/raw/jar"), bytes("the-jar"));

        assertThat(seenSibling).containsExactly("the-pom");
    }
}
