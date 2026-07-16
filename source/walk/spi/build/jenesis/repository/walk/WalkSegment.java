package build.jenesis.repository.walk;

import module java.base;

/**
 * One claimable slice of a walk pass: a half-open key range {@code [from, to)} under one root in the total
 * lexicographic key order ({@code null} bounds run to the root's start / end), plus the claim embedded in the same
 * compare-and-set store object ({@code walks/<consumer>/segments/<nn>}) - {@code state}, the {@code holder} worker,
 * the claim's {@code expiry}, and the {@code cursor} high-water mark of the last committed checkpoint. Because claim
 * and cursor live in one object, a checkpoint commit <em>is</em> the lease renewal (one write extends the expiry),
 * and the two can never diverge: a worker whose commit loses the compare-and-set has provably lost the whole claim
 * and stops. A claim is taken only over a pending or expired segment - never a live holder's - and a taken-over
 * segment resumes from {@code cursor}, so node death costs at most one checkpoint stride of re-visits, never a
 * restart.
 */
public record WalkSegment(long generation,
                          int index,
                          String root,
                          String from,
                          String to,
                          State state,
                          String holder,
                          Instant expiry,
                          String cursor) {

    /** Whether a worker may claim this segment now: never started, or its holder's lease ran out. */
    public boolean claimable(Instant now) {
        return state == State.PENDING || state == State.CLAIMED && expiry != null && !expiry.isAfter(now);
    }

    /** The claim lifecycle; {@code PENDING} also stands in for a stale object left by an earlier generation. */
    public enum State {
        PENDING,
        CLAIMED,
        DONE
    }
}
