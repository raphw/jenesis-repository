package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.StoreInvariants;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mark-sweep collector's data-safety core over a real filesystem store: an orphan is condemned by one pass
 * and collected only by a later one - never by the pass that first judged it - while a referenced, re-linked or
 * unrecognisable blob is never deleted, and the {@code gc/} bookkeeping converges to nothing on a clean store.
 */
class MarkSweepTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void an_orphan_is_condemned_then_collected_and_a_referenced_blob_never_is() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        GcPlan first = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(first.complete()).isTrue();
        assertThat(first.condemned()).isEqualTo(1);
        assertThat(first.collected()).isZero();
        assertThat(store.exists("blobs/" + orphan)).as("the first judgment condemns, never deletes").isTrue();
        assertThat(store.exists("gc/condemned/" + orphan)).isTrue();

        GcPlan second = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(second.complete()).isTrue();
        assertThat(second.collected()).isEqualTo(1);
        assertThat(second.sample()).containsExactly(orphan);
        assertThat(store.exists("blobs/" + orphan)).isFalse();
        assertThat(store.exists("gc/condemned/" + orphan)).as("a collected blob leaves no marker").isFalse();
        assertThat(store.exists("blobs/" + kept)).isTrue();
        new StoreInvariants(store).assertConsistent();
    }

    @Test
    void a_blob_written_between_passes_gets_a_full_interval_of_grace() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        var _ = collector().collect(store, List.of("publish"), clock.instant());

        String late = publication.storeBlob(bytes("in flight"));
        GcPlan second = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(second.condemned()).isEqualTo(1);
        assertThat(store.exists("blobs/" + late))
                .as("a blob younger than one pass is condemned at most, never deleted").isTrue();

        GcPlan third = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(third.collected()).isEqualTo(1);
        assertThat(store.exists("blobs/" + late)).isFalse();
    }

    @Test
    void a_condemned_blob_relinked_through_publication_is_never_collected() throws IOException {
        // The dedup re-publish race: identical content dedupes to the blob a pass already condemned; the re-link
        // clears the marker on the write path, so the next sweep has nothing due.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String blob = publication.storeBlob(bytes("deduped"));
        var _ = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(store.exists("gc/condemned/" + blob)).isTrue();

        publication.link("/maven/back.jar", blob);
        assertThat(store.exists("gc/condemned/" + blob)).as("the link un-condemned the blob").isFalse();

        GcPlan next = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(next.collected()).isZero();
        assertThat(store.exists("blobs/" + blob)).isTrue();
    }

    @Test
    void a_pointer_written_outside_publication_is_spared_by_the_next_mark() throws IOException {
        // A blobs-namespace format links its pointers under its own roots, without Publication.link's marker
        // clear: the next pass's mark sees the pointer, spares the blob and removes the stale marker itself.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String blob = publication.storeBlob(bytes("npm tarball"));
        var _ = collector().collect(store, List.of("publish", "npm"), clock.instant());
        assertThat(store.exists("gc/condemned/" + blob)).isTrue();

        store.writeVersioned("npm/lodash/4.0.0/pointer", blob.getBytes(StandardCharsets.UTF_8), null);
        GcPlan next = collector().collect(store, List.of("publish", "npm"), clock.instant());
        assertThat(next.collected()).isZero();
        assertThat(next.spared()).isEqualTo(1);
        assertThat(store.exists("blobs/" + blob)).isTrue();
        assertThat(store.exists("gc/condemned/" + blob)).as("the stale marker converged away").isFalse();
    }

    @Test
    void only_recognised_content_addressed_objects_are_ever_judged() throws IOException {
        ArtifactStore store = store();
        store.writeVersioned("blobs/not-a-content-hash", "junk".getBytes(StandardCharsets.UTF_8), null);
        byte[] large = new byte[2048]; // an oversized "pointer" leaf is other metadata, skipped unread
        store.writeVersioned("publish/maven/metadata.xml", large, null);

        var _ = collector().collect(store, List.of("publish"), clock.instant());
        var _ = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(store.exists("blobs/not-a-content-hash"))
                .as("a name that is no SHA-256 is never judged, let alone deleted").isTrue();
        assertThat(store.list("gc/condemned")).isEmpty();
        assertThat(store.exists("publish/maven/metadata.xml")).isTrue();
    }

    @Test
    void bookkeeping_converges_and_superseded_reference_shards_are_dropped() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));
        String stale = "ab".repeat(32); // a marker whose blob is long gone - crash residue
        store.writeVersioned("gc/condemned/" + stale,
                "pass=1\nsince=2026-07-01T00:00:00Z".getBytes(StandardCharsets.UTF_8), null);

        var _ = collector().collect(store, List.of("publish"), clock.instant());
        GcPlan second = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(second.collected()).isEqualTo(1);
        assertThat(store.exists("gc/condemned/" + stale)).as("a blob-less marker is swept").isFalse();
        assertThat(store.list("gc/1")).as("pass 1's reference shards were superseded and dropped").isEmpty();

        GcPlan converged = collector().collect(store, List.of("publish"), clock.instant());
        assertThat(converged.complete()).isTrue();
        assertThat(converged.isEmpty()).as("a re-run over a converged store changes nothing").isTrue();
        assertThat(store.exists("blobs/" + kept)).isTrue();
        assertThat(store.exists("blobs/" + orphan)).isFalse();
    }

    @Test
    void a_sweep_never_deletes_after_its_reference_shards_are_superseded() throws IOException {
        // The lease fence: a paused or lease-expired sweep worker that resumes after a newer mark generation has
        // superseded (and whose converge may have dropped) its reference shards must not judge a blob against the
        // emptied shards and delete a still-referenced one. Standing in for that, a decorator makes the mark manifest
        // report a newer generation during the sweep - exactly what a concurrent node's later mark presents - and the
        // sweep must refuse the delete, deferring it rather than deleting against a superseded reference set.
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        // Pass 1 condemns the orphan; a normal pass 2 would collect it.
        assertThat(collector().collect(store, List.of("publish"), clock.instant()).condemned()).isEqualTo(1);

        ArtifactWalk inflating = new GenerationInflatingMarkWalk(
                new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
        GcPlan fenced = new MarkSweepGarbageCollector(inflating).collect(store, List.of("publish"), clock.instant());
        assertThat(fenced.collected()).as("a superseded reference set fences the delete").isZero();
        assertThat(store.exists("blobs/" + orphan))
                .as("the orphan is deferred, never deleted against dropped shards").isTrue();

        // With the shards standing again (a normal walk) the deferred orphan is reclaimed - the fence only ever
        // delays a delete, never loses one - and the referenced blob is untouched throughout.
        assertThat(collector().collect(store, List.of("publish"), clock.instant()).collected()).isEqualTo(1);
        assertThat(store.exists("blobs/" + orphan)).isFalse();
        assertThat(store.exists("blobs/" + kept)).isTrue();
    }

    /** Forwards to a real walk but, once the sweep phase begins, reports the mark pass one generation newer than it
     *  truly is - standing in for a concurrent node whose later mark has superseded (and whose converge may have
     *  dropped) this sweep's reference shards. The mark phase itself runs against the true generation, so the shards
     *  are written where the sweep loads them; only the fence's re-read of the mark manifest sees the advance. */
    private static final class GenerationInflatingMarkWalk implements ArtifactWalk {

        private final ArtifactWalk delegate;
        private volatile boolean sweeping;

        private GenerationInflatingMarkWalk(ArtifactWalk delegate) {
            this.delegate = delegate;
        }

        @Override
        public WalkPass walk(ArtifactStore store, String consumer, List<String> roots, KeyVisitor visitor)
                throws IOException {
            if (consumer.equals("gc-sweep")) {
                sweeping = true;
            }
            return delegate.walk(store, consumer, roots, visitor);
        }

        @Override
        public Optional<WalkPass> pass(ArtifactStore store, String consumer) throws IOException {
            Optional<WalkPass> real = delegate.pass(store, consumer);
            if (sweeping && consumer.equals("gc-mark") && real.isPresent()) {
                WalkPass pass = real.get();
                return Optional.of(new WalkPass(pass.generation() + 1, pass.started(), pass.roots(),
                        pass.segments(), pass.done(), pass.status()));
            }
            return real;
        }

        @Override
        public List<WalkSegment> segments(ArtifactStore store, String consumer) throws IOException {
            return delegate.segments(store, consumer);
        }
    }

    @Test
    void empty_and_reserved_pointer_roots_are_refused() {
        ArtifactStore store = store();
        assertThatThrownBy(() -> collector().collect(store, List.of(), clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String reserved : List.of("blobs", "gc", "walks")) {
            assertThatThrownBy(() -> collector().collect(store, List.of("publish", reserved), clock.instant()))
                    .as("marking %s as a pointer root is a caller bug", reserved)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> collector().plan(store, List.of(reserved), clock.instant()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
