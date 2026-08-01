package build.jenesis.repository.walk.test;

import build.jenesis.repository.walk.WalkSegment;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim predicate {@link WalkSegment#claimable(Instant)} - a worker may take a segment that was never started
 * ({@code PENDING}) or whose holder's lease has run out (an expired {@code CLAIMED}), but never a live holder's
 * ({@code CLAIMED} with an expiry still ahead), a completed one ({@code DONE}), or a {@code CLAIMED} whose expiry is
 * unrecorded ({@code null}, treated as live so a missing-expiry object is never stolen). Checked right across the
 * {@code now} boundary, where an expiry equal to {@code now} counts as elapsed (the lease is not still ahead).
 */
class WalkSegmentClaimableTest {

    private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

    private static WalkSegment segment(WalkSegment.State state, Instant expiry) {
        return new WalkSegment(1, 0, "publish", null, null, state, "node/1", expiry, null);
    }

    @Test
    void a_pending_segment_is_always_claimable() {
        assertThat(segment(WalkSegment.State.PENDING, null).claimable(NOW))
                .as("never started - free to claim").isTrue();
    }

    @Test
    void a_live_claim_is_not_claimable() {
        assertThat(segment(WalkSegment.State.CLAIMED, NOW.plusSeconds(60)).claimable(NOW))
                .as("a live holder's lease is still ahead - refuse, do not steal").isFalse();
    }

    @Test
    void an_expired_claim_is_claimable() {
        assertThat(segment(WalkSegment.State.CLAIMED, NOW.minusSeconds(1)).claimable(NOW))
                .as("the holder's lease ran out - free to reclaim").isTrue();
    }

    @Test
    void a_claim_whose_expiry_equals_now_is_claimable() {
        assertThat(segment(WalkSegment.State.CLAIMED, NOW).claimable(NOW))
                .as("an expiry at the boundary is elapsed - the lease is not still ahead of now").isTrue();
    }

    @Test
    void a_claim_with_no_recorded_expiry_is_not_claimable() {
        assertThat(segment(WalkSegment.State.CLAIMED, null).claimable(NOW))
                .as("a CLAIMED object with no expiry is treated as live, never stolen").isFalse();
    }

    @Test
    void a_done_segment_is_never_claimable() {
        assertThat(segment(WalkSegment.State.DONE, NOW.minusSeconds(60)).claimable(NOW))
                .as("a completed segment is finished, even long past its expiry").isFalse();
    }
}
