package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mark-sweep collector is its own {@link build.jenesis.repository.observation.ObservabilitySource}: once it has
 * run a {@code collect} it reports {@code jenesis.gc.condemned} (the in-flight condemned set), the
 * {@code jenesis.gc.collected} counter and a {@code jenesis.gc.lastrun} task status; a collector that has not run
 * (as with no collector installed or selected at all - the no-op default) reports nothing. Exercised against a real
 * filesystem store, without the server or Micrometer.
 */
class GcObservabilityTest {

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
    void a_collector_that_has_never_run_reports_nothing() {
        MarkSweepGarbageCollector collector = collector();

        assertThat(collector.metrics()).isEmpty();
        assertThat(collector.taskStatuses()).isEmpty();
        assertThat(collector.healthChecks()).isEmpty();
    }

    @Test
    void the_first_collect_condemns_and_the_signals_report_the_in_flight_set() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        var _ = publication.storeBlob(bytes("orphan"));

        MarkSweepGarbageCollector collector = collector();
        collector.collect(store, List.of("publish"), clock.instant());

        assertThat(collector.metrics()).satisfiesExactlyInAnyOrder(
                condemned -> {
                    assertThat(condemned.name()).isEqualTo("jenesis.gc.condemned");
                    assertThat(condemned.kind()).isEqualTo(Metric.Kind.GAUGE);
                    assertThat(condemned.value()).isEqualTo(1.0);
                    assertThat(condemned.description()).isNotBlank();
                },
                collected -> {
                    assertThat(collected.name()).isEqualTo("jenesis.gc.collected");
                    assertThat(collected.kind()).isEqualTo(Metric.Kind.COUNTER);
                    assertThat(collected.value()).isZero();
                    assertThat(collected.description()).isNotBlank();
                });

        assertThat(collector.taskStatuses()).singleElement().satisfies(lastrun -> {
            assertThat(lastrun.name()).isEqualTo("jenesis.gc.lastrun");
            assertThat(lastrun.state()).isEqualTo(TaskStatus.State.IDLE);
            assertThat(lastrun.lastRun()).isNotNull();
            assertThat(lastrun.description()).isNotBlank();
        });
    }

    @Test
    void a_second_collect_reclaims_the_orphan_and_climbs_the_collected_counter() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        var _ = publication.storeBlob(bytes("orphan"));

        MarkSweepGarbageCollector collector = collector();
        collector.collect(store, List.of("publish"), clock.instant()); // condemn
        collector.collect(store, List.of("publish"), clock.instant()); // collect

        assertThat(collector.metrics())
                .filteredOn(metric -> metric.name().equals("jenesis.gc.collected"))
                .singleElement().extracting(Metric::value).isEqualTo(1.0);
        assertThat(collector.metrics())
                .filteredOn(metric -> metric.name().equals("jenesis.gc.condemned"))
                .singleElement().extracting(Metric::value)
                .as("the orphan was reclaimed, so nothing is left condemned").isEqualTo(0.0);
    }

    @Test
    void every_signal_name_follows_the_jenesis_gc_grammar() throws IOException {
        ArtifactStore store = store();
        MarkSweepGarbageCollector collector = collector();
        collector.collect(store, List.of("publish"), clock.instant());

        assertThat(collector.metrics()).extracting(Metric::name)
                .allSatisfy(name -> assertThat(name).matches("jenesis\\.gc\\..+"));
        assertThat(collector.taskStatuses()).extracting(TaskStatus::name)
                .allSatisfy(name -> assertThat(name).matches("jenesis\\.gc\\..+"));
    }

    @Test
    void the_signals_collect_into_the_report_the_consumers_read() throws IOException {
        ArtifactStore store = store();
        var _ = new Publication(store).storeBlob(bytes("orphan"));
        MarkSweepGarbageCollector collector = collector();
        collector.collect(store, List.of("publish"), clock.instant());

        ObservabilityReport report = ObservabilityReport.from(List.of(collector));

        assertThat(report.metrics()).extracting(Metric::name)
                .containsExactly("jenesis.gc.collected", "jenesis.gc.condemned"); // name-sorted
        assertThat(report.tasks()).extracting(TaskStatus::name).containsExactly("jenesis.gc.lastrun");
    }
}
