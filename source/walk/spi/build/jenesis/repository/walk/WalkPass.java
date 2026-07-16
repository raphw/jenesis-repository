package build.jenesis.repository.walk;

import module java.base;

/**
 * One walk pass as recorded in the consumer's manifest ({@code walks/<consumer>/manifest}): a monotonically
 * increasing generation (the pass id every segment claim embeds, so a stale segment object from an earlier pass is
 * recognisably reclaimable), the roots the pass enumerates, when it started, how its segments stand, and whether it
 * is still running. The pass is complete when every segment is done; the last finisher flips the manifest, and the
 * next {@link ArtifactWalk#walk} then starts generation + 1.
 */
public record WalkPass(long generation, Instant started, List<String> roots, int segments, int done, Status status) {

    public WalkPass {
        roots = List.copyOf(roots);
    }

    /** Whether the pass has enumerated everything - the {@code onPassCompleted} moment for a consumer that
     *  commits or compacts at pass end. */
    public boolean complete() {
        return status == Status.COMPLETE;
    }

    /** A pass is running until its last segment is done. */
    public enum Status {
        ACTIVE,
        COMPLETE
    }
}
