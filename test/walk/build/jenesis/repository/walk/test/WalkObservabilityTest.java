package build.jenesis.repository.walk.test;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared-walk engine is its own {@link build.jenesis.repository.observation.ObservabilitySource}: once it has
 * driven a pass it reports {@code jenesis.walk.segments} (a bounded gauge of the pass's done segments against its
 * segment count), {@code jenesis.walk.resumes} (a counter of segments this node reclaimed from an expired holder)
 * and a {@code jenesis.walk.pass} task status; a never-run walk reports nothing at all. Exercised against a real
 * {@link build.jenesis.repository.store.filesystem.FilesystemArtifactStore}, without the server or Micrometer.
 */
class WalkObservabilityTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store(String name) {
        Path scoped = root.resolve(name);
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? scoped.toString() : null);
    }

    private void seed(ArtifactStore store, String... names) throws IOException {
        for (String name : names) {
            store.writeVersioned("publish/" + name, name.getBytes(StandardCharsets.UTF_8), null);
        }
    }

    @Test
    void a_never_run_walk_reports_nothing() {
        StoreArtifactWalk walk = new StoreArtifactWalk(1000, 32, Duration.ofMinutes(15), clock);

        assertThat(walk.metrics()).isEmpty();
        assertThat(walk.taskStatuses()).isEmpty();
        assertThat(walk.healthChecks()).isEmpty();
    }

    @Test
    void a_completed_pass_reports_a_bounded_segments_gauge_a_resumes_counter_and_the_pass_status() throws IOException {
        ArtifactStore store = store("done");
        seed(store, "a", "b", "c");
        StoreArtifactWalk walk = new StoreArtifactWalk(1000, 1, Duration.ofMinutes(15), clock);

        List<String> visited = new ArrayList<>();
        walk.walk(store, "test", List.of("publish"), visited::add);
        assertThat(visited).hasSize(3);

        assertThat(walk.metrics()).satisfiesExactlyInAnyOrder(
                segments -> {
                    assertThat(segments.name()).isEqualTo("jenesis.walk.segments");
                    assertThat(segments.kind()).isEqualTo(Metric.Kind.GAUGE);
                    assertThat(segments.value()).isEqualTo(1.0);
                    assertThat(segments.limit()).hasValue(1.0);
                    assertThat(segments.usage()).hasValue(1.0);
                    assertThat(segments.description()).isNotBlank();
                },
                resumes -> {
                    assertThat(resumes.name()).isEqualTo("jenesis.walk.resumes");
                    assertThat(resumes.kind()).isEqualTo(Metric.Kind.COUNTER);
                    assertThat(resumes.value()).isZero();
                    assertThat(resumes.limit()).isEmpty();
                    assertThat(resumes.description()).isNotBlank();
                });

        assertThat(walk.taskStatuses()).singleElement().satisfies(pass -> {
            assertThat(pass.name()).isEqualTo("jenesis.walk.pass");
            assertThat(pass.state()).isEqualTo(TaskStatus.State.IDLE);
            assertThat(pass.lastRun()).isNotNull();
            assertThat(pass.outcome()).contains("generation 1");
            assertThat(pass.description()).isNotBlank();
        });
    }

    @Test
    void every_signal_name_follows_the_jenesis_walk_grammar() throws IOException {
        ArtifactStore store = store("grammar");
        seed(store, "x");
        StoreArtifactWalk walk = new StoreArtifactWalk(1000, 1, Duration.ofMinutes(15), clock);
        walk.walk(store, "test", List.of("publish"), key -> { });

        assertThat(walk.metrics()).extracting(Metric::name)
                .allSatisfy(name -> assertThat(name).matches("jenesis\\.walk\\..+"));
        assertThat(walk.taskStatuses()).extracting(TaskStatus::name)
                .allSatisfy(name -> assertThat(name).matches("jenesis\\.walk\\..+"));
    }

    @Test
    void a_takeover_of_an_expired_holders_segment_climbs_the_resumes_counter() throws IOException {
        ArtifactStore store = store("resume");
        seed(store, "a", "b", "c", "d", "e");
        // checkpoint 1 commits a cursor per key; a crash mid-segment leaves it CLAIMED and expiring, so the same
        // instance's next walk takes the expired segment over from the last committed cursor - a resume.
        StoreArtifactWalk walk = new StoreArtifactWalk(1, 1, Duration.ofMinutes(10), clock);

        List<String> before = new ArrayList<>();
        assertThatThrownBy(() -> walk.walk(store, "test", List.of("publish"), key -> {
            before.add(key);
            if (before.size() == 3) {
                throw new IOException("crash mid-segment");
            }
        })).hasMessageContaining("crash mid-segment");
        assertThat(walk.metrics()).as("the crashed pass never returned, so nothing is observed yet").isEmpty();

        clock.advance(Duration.ofMinutes(11)); // let the abandoned claim expire
        List<String> after = new ArrayList<>();
        walk.walk(store, "test", List.of("publish"), after::add);

        assertThat(walk.metrics())
                .filteredOn(metric -> metric.name().equals("jenesis.walk.resumes"))
                .singleElement()
                .extracting(Metric::value)
                .isEqualTo(1.0);
    }

    @Test
    void the_signals_collect_into_the_report_the_consumers_read() throws IOException {
        ArtifactStore store = store("report");
        seed(store, "one", "two");
        StoreArtifactWalk walk = new StoreArtifactWalk(1000, 1, Duration.ofMinutes(15), clock);
        walk.walk(store, "test", List.of("publish"), key -> { });

        ObservabilityReport report = ObservabilityReport.from(List.of(walk));

        assertThat(report.metrics()).extracting(Metric::name)
                .containsExactly("jenesis.walk.resumes", "jenesis.walk.segments"); // name-sorted
        assertThat(report.tasks()).extracting(TaskStatus::name).containsExactly("jenesis.walk.pass");
    }
}
