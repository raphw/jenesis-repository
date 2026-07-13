package build.jenesis.repository.observation;

/**
 * The health of a single check or of an aggregate. The constants are declared in ascending severity so a report can
 * collapse many checks into one verdict with {@link #worst}: {@link #UP} is the quiet default, an {@link #UNKNOWN}
 * check (a source that could not determine its state) is surfaced but not paged, a {@link #DEGRADED} one warns
 * (back-pressure, near a limit) and a single {@link #DOWN} check is the state worth paging on. Absent any check an
 * aggregate is {@link #UP} - nothing is reporting trouble.
 */
public enum Health {

    /** Healthy. */
    UP,
    /** A source could not determine its state. */
    UNKNOWN,
    /** Working, but with a warning worth surfacing. */
    DEGRADED,
    /** Broken - the state worth paging on. */
    DOWN;

    /** The more severe of this state and {@code other} (a later constant is more severe), for collapsing many checks
     *  into one overall verdict. */
    public Health worst(Health other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
