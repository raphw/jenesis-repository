package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.Withheld;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The withhold-change feed (Audit-23, phase P3): the two durable withhold conventions - the {@code withheld/<hash>}
 * marker and the {@code /quarantine<servedPath>} review pointer - fire a transition-only after-commit signal on their
 * one free-core choke points, so a durable derived-metadata consumer (a published index) can retract a retroactively
 * held coordinate rather than trusting only the emit-time screen. This pins the free half of that feed:
 * <ul>
 *   <li>the marker face - {@link Withheld#mark} / {@link Withheld#clear} (static, firing through the discovered
 *       {@code Publication.OBSERVERS}, observed here by the ServiceLoader-registered {@link RecordingWithholdObserver})
 *       - fires once per actual transition and never on an idempotent converge re-mark, keyed by the content hash with
 *       a {@code null} path (one marker retracts every alias);</li>
 *   <li>the pointer face - {@link Publication#link} of a FRESH {@code /quarantine<path>} pointer and its
 *       {@link Publication#unpublish} - fires with the served path (the {@code /quarantine} prefix stripped) and the
 *       pointer's hash, over the caller's injected observer list;</li>
 *   <li>a contained consumer failure never fails a mark (marking must not fail open because a consumer is down);</li>
 *   <li>a non-quarantine link raises no withhold signal - the publish hot path is untouched.</li>
 * </ul>
 */
class WithholdFeedTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    private static final String HASH = "a".repeat(64);

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        RecordingWithholdObserver.reset();
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    // ---- marker face (Withheld.mark / clear, via the discovered OBSERVERS) ----

    @Test
    void a_fresh_marker_fires_onWithheld_once_and_a_re_mark_is_silent() throws IOException {
        Withheld.mark(store, HASH);
        Withheld.mark(store, HASH);   // the sweeps' idempotent converge re-mark - absent-guard makes it a no-op

        assertThat(RecordingWithholdObserver.WITHHELD)
                .as("transition-only: a fresh marker fires once, a re-mark of a marked hash is silent").hasSize(1);
        assertThat(RecordingWithholdObserver.WITHHELD.getFirst().hash())
                .as("the marker route carries the content hash").isEqualTo(HASH);
        assertThat(RecordingWithholdObserver.WITHHELD.getFirst().path())
                .as("one marker retracts every alias, so the path is null").isNull();
    }

    @Test
    void clearing_a_present_marker_fires_onWithholdCleared_once_and_a_re_clear_is_silent() throws IOException {
        Withheld.mark(store, HASH);
        Withheld.clear(store, HASH);
        Withheld.clear(store, HASH);   // clear of an unmarked hash is a no-op

        assertThat(RecordingWithholdObserver.CLEARED)
                .as("transition-only: a clear of a present marker fires once, a re-clear is silent").hasSize(1);
        assertThat(RecordingWithholdObserver.CLEARED.getFirst().hash()).isEqualTo(HASH);
        assertThat(RecordingWithholdObserver.CLEARED.getFirst().path()).isNull();
    }

    @Test
    void a_contained_observer_failure_never_fails_a_mark() {
        RecordingWithholdObserver.fail = true;
        assertThatCode(() -> Withheld.mark(store, HASH))
                .as("a down feed consumer is logged and contained - marking must never fail open").doesNotThrowAnyException();
        assertThat(store.exists("withheld/" + HASH)).as("the marker still landed").isTrue();
        RecordingWithholdObserver.fail = false;
    }

    // ---- pointer face (Publication.link / unpublish of /quarantine paths, over injected observers) ----

    @Test
    void a_fresh_quarantine_link_fires_onWithheld_stripped_and_a_relink_is_silent() throws IOException {
        Recorder recorder = new Recorder();
        Publication publication = new Publication(store, List.of(), List.of(recorder));
        String hash = publication.storeBlob(bytes("held"));

        publication.link("/quarantine/maven/g/a/1/a.jar", hash);
        publication.link("/quarantine/maven/g/a/1/a.jar", hash);   // an overwrite, not a fresh link - no new signal

        assertThat(recorder.withheld).as("only the FRESH /quarantine link fires the transition-ON signal").hasSize(1);
        assertThat(recorder.withheld.getFirst().path())
                .as("the served path with the /quarantine prefix stripped").isEqualTo("/maven/g/a/1/a.jar");
        assertThat(recorder.withheld.getFirst().hash()).as("the pointer's hash rides the event").isEqualTo(hash);
    }

    @Test
    void unpublishing_a_quarantine_pointer_fires_onWithholdCleared_stripped_alongside_onDeleted() throws IOException {
        Recorder recorder = new Recorder();
        Publication publication = new Publication(store, List.of(), List.of(recorder));
        String hash = publication.storeBlob(bytes("held"));
        publication.link("/quarantine/maven/g/a/1/a.jar", hash);

        publication.unpublish("/quarantine/maven/g/a/1/a.jar");

        assertThat(recorder.cleared).as("removing the review pointer fires the transition-OFF signal").hasSize(1);
        assertThat(recorder.cleared.getFirst().path())
                .as("the served path with the /quarantine prefix stripped").isEqualTo("/maven/g/a/1/a.jar");
        assertThat(recorder.cleared.getFirst().hash()).isEqualTo(hash);
        assertThat(recorder.deleted).as("the existing onDeleted still fires, unchanged, carrying the /quarantine path")
                .containsExactly("/quarantine/maven/g/a/1/a.jar");
    }

    @Test
    void a_non_quarantine_link_fires_no_withhold_signal() throws IOException {
        Recorder recorder = new Recorder();
        Publication publication = new Publication(store, List.of(), List.of(recorder));
        String hash = publication.storeBlob(bytes("plain"));

        publication.link("/maven/g/a/1/a.jar", hash);

        assertThat(recorder.withheld).as("an ordinary publish link raises no withhold-change signal").isEmpty();
    }

    /** An injected observer capturing the instance-path withhold-feed events plus the ordinary removals beside them. */
    private static final class Recorder implements PublicationObserver {
        private final List<ArtifactDescriptor> withheld = new ArrayList<>();
        private final List<ArtifactDescriptor> cleared = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        }

        @Override
        public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) {
            deleted.add(artifact.path());
        }

        @Override
        public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) {
            withheld.add(subject);
        }

        @Override
        public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) {
            cleared.add(subject);
        }
    }
}
