package build.jenesis.repository.usage.test;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.usage.BatchingKeyUsageTracker;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shutdown-drain contract of {@link BatchingKeyUsageTracker#close()}: a clean shutdown forfeits no accepted hit.
 * When the worker stops it drains whatever the interrupted worker left queued and flushes every residual per-credential
 * delta to the store - unconditionally, bypassing the at-most-once-per-UTC-day gate the background {@code drain}
 * applies. So a credential already flushed once today whose same-day tail is still queued at shutdown has that tail
 * persisted on close, rather than stranded until the process ends (a lost accepted hit). {@code KeyUsageTrackerTest}
 * asserts only the {@code alive()} transition on close; this asserts the drain-and-flush the close actually performs.
 *
 * <p>Driven synchronously without ever starting the worker (so {@code close()} takes its deterministic
 * worker-terminated-or-never-started path, with no thread racing the final flush): hits are enqueued through
 * {@link BatchingKeyUsageTracker#record}, then {@code close()} must drain the queue and flush the tail.
 */
class BatchingKeyUsageCloseTest {

    @TempDir
    Path root;

    private Authorization authorization;
    private String flushedToday;
    private String neverSeen;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
        flushedToday = Authorization.hash(Authorization.mint("acme"));
        neverSeen = Authorization.hash(Authorization.mint("acme"));
        authorization.provision("acme", flushedToday, "already-flushed", null);
        authorization.provision("acme", neverSeen, "fresh-at-shutdown", null);
    }

    @Test
    void close_drains_the_queued_tail_and_flushes_it_including_a_credential_already_flushed_today() throws IOException {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);

        // Flush one credential once, now, so its writtenDay is today's UTC date - exactly the day close() will run on.
        // A close() that reused the background drain's same-day gate would then strand this credential's later tail.
        tracker.drain(List.of(new BatchingKeyUsageTracker.Hit("acme", flushedToday, "10.0.0.1")), Instant.now());
        assertThat(authorization.credential("acme", flushedToday).orElseThrow().useCount())
                .as("the credential is flushed once today").isEqualTo(1);

        // Enqueue a same-day tail for that already-flushed credential, plus a credential seen for the first time - none
        // of it is flushed yet, because the worker was never started so nothing drains the queue.
        tracker.record("acme", flushedToday, "10.0.0.2");
        tracker.record("acme", flushedToday, "10.0.0.3");
        tracker.record("acme", neverSeen, "10.0.0.9");
        assertThat(authorization.credential("acme", flushedToday).orElseThrow().useCount())
                .as("the queued same-day tail is not flushed before close").isEqualTo(1);
        assertThat(authorization.credential("acme", neverSeen).orElseThrow().useCount())
                .as("the queued first-sighting is not flushed before close").isZero();

        tracker.close();

        // close() drained the tail (both credentials' queued hits) and flushed every residual delta unconditionally.
        Authorization.Credential flushed = authorization.credential("acme", flushedToday).orElseThrow();
        assertThat(flushed.useCount())
                .as("the already-flushed-today credential's same-day tail is persisted on close, not stranded")
                .isEqualTo(3);
        assertThat(flushed.lastUsedAddress()).as("with its most recent address").isEqualTo("10.0.0.3");
        Authorization.Credential fresh = authorization.credential("acme", neverSeen).orElseThrow();
        assertThat(fresh.useCount())
                .as("and a credential first seen in the queued tail is flushed too - no accepted hit lost on shutdown")
                .isEqualTo(1);
        assertThat(fresh.lastUsedAddress()).isEqualTo("10.0.0.9");
    }
}
