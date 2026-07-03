package build.jenesis.repository.test;

import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.usage.BatchingKeyUsageTracker;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The usage tracker accumulates a per-credential request count and last source address and flushes them through
 * {@link Authorization#recordUsed} at most once per day, so the persisted count converges without a store write per
 * request.
 */
class KeyUsageTrackerTest {

    @TempDir
    Path root;

    private Authorization authorization;
    private String hash;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
        hash = Authorization.hash(Authorization.mint("acme"));
        authorization.provision("acme", hash, "k", null);
    }

    @Test
    void it_counts_every_hit_and_keeps_the_last_address_flushing_once_per_day() throws IOException {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);
        Instant morning = Instant.parse("2026-06-30T08:00:00Z");

        tracker.drain(List.of(new BatchingKeyUsageTracker.Hit("acme", hash, "10.0.0.1"),
                new BatchingKeyUsageTracker.Hit("acme", hash, "10.0.0.2")), morning);
        Authorization.Credential first = authorization.credential("acme", hash).orElseThrow();
        assertThat(first.useCount()).as("both hits counted").isEqualTo(2);
        assertThat(first.lastUsedAddress()).as("the most recent address").isEqualTo("10.0.0.2");

        tracker.drain(List.of(new BatchingKeyUsageTracker.Hit("acme", hash, "10.0.0.3")), morning.plus(Duration.ofHours(1)));
        assertThat(authorization.credential("acme", hash).orElseThrow().useCount())
                .as("a same-day hit accumulates but does not write again").isEqualTo(2);

        tracker.drain(List.of(new BatchingKeyUsageTracker.Hit("acme", hash, "10.0.0.4")), morning.plus(Duration.ofDays(1)));
        Authorization.Credential next = authorization.credential("acme", hash).orElseThrow();
        assertThat(next.useCount()).as("the next day flushes the accumulated delta").isEqualTo(4);
        assertThat(next.lastUsedAddress()).isEqualTo("10.0.0.4");
    }

    @Test
    void the_worker_is_not_alive_until_started_and_joins_on_close() {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);
        assertThat(tracker.alive()).as("not started").isFalse();
        tracker.start();
        assertThat(tracker.alive()).as("running after start").isTrue();
        tracker.close();
        assertThat(tracker.alive()).as("joined on close").isFalse();
    }

    @Test
    void hits_past_the_queue_capacity_are_counted_as_dropped() {
        BatchingKeyUsageTracker tracker = new BatchingKeyUsageTracker(authorization, true);
        for (int index = 0; index < 100_100; index++) {
            tracker.record("acme", hash, null);
        }
        assertThat(tracker.dropped()).as("offers past the 100k bound are dropped").isGreaterThan(0);
    }
}
