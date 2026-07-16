package build.jenesis.repository.gc;

import module java.base;

/**
 * The outcome of a garbage-collection pass or dry run - computed before anything is deleted, so it can be
 * previewed and reported, matching the retention sweeper's plan shape. From {@link GarbageCollector#plan} the
 * counters describe what a collection would do right now (nothing was written); from
 * {@link GarbageCollector#collect} they describe what the pass actually did.
 *
 * @param complete   whether the judgment rests on a completed enumeration: a plan with no completed pass to judge
 *                   by, or a collection whose shared walk still had segments held by another node, reports
 *                   {@code false} - it is a partial answer, not an empty store
 * @param condemned  blobs newly judged unreferenced this pass and marked for the <em>next</em> one - never deleted
 *                   by the pass that first judged them (always {@code 0} from a dry run)
 * @param spared     condemned markers cleared because the blob turned out to be referenced again - the dedup
 *                   re-publish that re-linked content an earlier pass judged orphaned (always {@code 0} from a
 *                   dry run)
 * @param collected  blobs due for deletion: reclaimed by {@code collect}, previewed by {@code plan}
 * @param sample     the first {@link #SAMPLE} collected hashes, for a console preview - the count above is the
 *                   whole truth where a mass eviction condemns more than fits a report
 */
public record GcPlan(boolean complete, long condemned, long spared, long collected, List<String> sample) {

    /** The most hashes {@link #sample} carries; {@link #collected} counts past it. */
    public static final int SAMPLE = 1000;

    public GcPlan {
        sample = List.copyOf(sample);
    }

    /** Whether the pass changed or would change nothing - the quiet steady state of a converged store. */
    public boolean isEmpty() {
        return condemned == 0 && spared == 0 && collected == 0;
    }
}
