package build.jenesis.repository.posture;

/**
 * How serious a {@link SecurityAdvisory} is. The constants are declared in ascending severity so a report can sort
 * critical-first with {@link #compareTo}: {@link #INFO} is a hardening hint worth noting, {@link #WARN} is a real
 * footgun a public deployment should fix, and {@link #CRITICAL} is a configuration that leaves the instance open or
 * unauthenticated - the one worth acting on now. A posture advisory is always at least {@link #INFO}; there is no
 * "healthy" severity, because a condition that is fine simply raises no advisory.
 */
public enum Severity {

    /** A hardening hint - safe enough, but there is a stronger default. */
    INFO,
    /** A real footgun a public deployment should fix. */
    WARN,
    /** The instance is open, unauthenticated or otherwise exposed - act now. */
    CRITICAL;

    /** The more severe of this severity and {@code other} (a later constant is more severe). */
    public Severity worst(Severity other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
