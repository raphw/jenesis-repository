package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dry run against an <em>incomplete</em> mark: when the current mark pass has not finished, {@code plan} may not
 * judge against its half-written shards (they would miss references and preview live blobs as due), so it falls back
 * to the most recent mark whose reference shards still stand. That fallback is {@code lastCompletedGeneration} - the
 * largest {@code gc/<n>} below the in-progress generation - not simply {@code generation - 1}, which matters after a
 * corrupt-manifest recovery re-bases the mark generation onto the wall clock: {@code generation - 1} would then point
 * {@code References} at a {@code gc/<clock-1>} that never existed and preview every condemned blob as due. Both the
 * ordinary sequential case and the clock-rebased case are exercised here.
 */
class GcIncompleteMarkPlanTest {

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

    private StoreArtifactWalk mark() {
        return new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Start a mark pass that turns the manifest over to a new generation, then crashes mid-enumeration - leaving an
     *  incomplete mark whose generation plan() must not judge against. */
    private void startAnIncompleteMark(ArtifactStore store) {
        assertThatThrownBy(() -> mark().walk(store, "gc-mark", List.of("publish"), key -> {
            throw new IOException("mark crash");
        })).hasMessageContaining("mark crash");
    }

    @Test
    void plan_over_an_incomplete_mark_judges_against_the_last_completed_generation() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        // A first full collect: mark generation 1 completes (its reference shards under gc/1), the orphan is condemned.
        assertThat(collector().collect(store, List.of("publish"), clock.instant()).complete()).isTrue();
        assertThat(store.exists("gc/condemned/" + orphan)).isTrue();
        assertThat(store.list("gc/1")).as("generation 1's reference shards stand").isNotEmpty();

        // A second mark pass turns the manifest over to generation 2 but crashes before completing.
        startAnIncompleteMark(store);
        assertThat(mark().pass(store, "gc-mark")).hasValueSatisfying(pass -> {
            assertThat(pass.generation()).isEqualTo(2);
            assertThat(pass.complete()).as("the mark is in progress, not complete").isFalse();
        });

        // plan() must fall back to generation 1 (its shards still stand) and preview the orphan condemned by pass 1.
        GcPlan plan = collector().plan(store, List.of("publish"), clock.instant());
        assertThat(plan.complete()).as("a completed earlier mark is available to judge by").isTrue();
        assertThat(plan.collected()).isEqualTo(1);
        assertThat(plan.sample()).containsExactly(orphan);
    }

    @Test
    void a_clock_rebased_incomplete_mark_still_finds_the_real_last_completed_generation() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        String orphan = publication.storeBlob(bytes("orphan"));

        // First full collect: mark generation 1 completes (shards under gc/1 name the kept blob), orphan condemned.
        assertThat(collector().collect(store, List.of("publish"), clock.instant()).complete()).isTrue();
        assertThat(store.list("gc/1")).isNotEmpty();

        // A referenced blob that also carries a stale condemned marker (condemned earlier, re-referenced but its
        // marker not yet cleared): plan must spare it because generation 1's shards name it. Were plan to judge
        // against generation-1 == clockGen-1 (a gc/<clock-1> that never existed), the kept blob would read
        // unreferenced and be wrongly previewed as due.
        store.writeVersioned("gc/condemned/" + kept,
                ("pass=1\nsince=" + clock.instant()).getBytes(StandardCharsets.UTF_8), null);

        // Corrupt the mark manifest, then start a mark: the manifest re-bases onto the wall clock (a huge
        // generation), incomplete.
        Object token = store.readVersioned("walks/gc-mark/manifest").orElseThrow().token();
        assertThat(store.writeVersioned("walks/gc-mark/manifest",
                "junk not a manifest".getBytes(StandardCharsets.UTF_8), token)).isTrue();
        startAnIncompleteMark(store);
        long clockGen = mark().pass(store, "gc-mark").orElseThrow().generation();
        assertThat(clockGen).as("the corrupt manifest re-based the mark generation onto the wall clock")
                .isEqualTo(clock.instant().toEpochMilli()).isGreaterThan(2);

        GcPlan plan = collector().plan(store, List.of("publish"), clock.instant());
        assertThat(plan.complete()).isTrue();
        assertThat(plan.collected()).as("only the orphan is due - not the referenced-but-condemned blob")
                .isEqualTo(1);
        assertThat(plan.sample())
                .as("the real generation-1 shards spare the referenced blob; a naive clock-1 would preview it as due")
                .containsExactly(orphan)
                .doesNotContain(kept);
    }
}
