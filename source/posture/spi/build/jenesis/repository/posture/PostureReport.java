package build.jenesis.repository.posture;

import module java.base;

/**
 * The single collected view every consumer reads - the console's Security-posture panel, the admin API and the boot log
 * all render <em>this</em>, so an advisory is defined in exactly one place. {@link #from} evaluates a set of
 * {@link SafetyAdvisor}s against the effective {@link Configuration} and sorts the result critical-first (then by id, a
 * stable order); {@link #discover} does the same over the {@link ServiceLoader}-installed advisors. A module that raises
 * nothing (a disabled feature, a safe configuration) simply adds nothing - the report degrades gracefully to whatever is
 * actually unsafe, and an empty report is the healthy state.
 */
public record PostureReport(List<SecurityAdvisory> advisories) {

    public PostureReport {
        advisories = List.copyOf(advisories);
    }

    /** Evaluate {@code advisors} against {@code config} and sort critical-first (ties broken by id). */
    public static PostureReport from(Iterable<? extends SafetyAdvisor> advisors, Configuration config) {
        List<SecurityAdvisory> collected = new ArrayList<>();
        for (SafetyAdvisor advisor : advisors) {
            collected.addAll(advisor.advise(config));
        }
        collected.sort(Comparator.comparing(SecurityAdvisory::severity, Comparator.reverseOrder())
                .thenComparing(SecurityAdvisory::id));
        return new PostureReport(collected);
    }

    /** Evaluate every {@link ServiceLoader}-discovered {@link SafetyAdvisor} against {@code config}. */
    public static PostureReport discover(Configuration config) {
        return from(ServiceLoader.load(SafetyAdvisor.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList(), config);
    }

    /** The total number of advisories - the count the console badge shows. */
    public int count() {
        return advisories.size();
    }

    /** The number of advisories at {@code severity}. */
    public long count(Severity severity) {
        return advisories.stream().filter(advisory -> advisory.severity() == severity).count();
    }

    /** The most severe advisory's severity, or empty when the report is clean. */
    public Optional<Severity> highest() {
        return advisories.stream().map(SecurityAdvisory::severity).max(Comparator.naturalOrder());
    }

    /** The advisories at {@code scope} - deployment-wide ones for a superadmin, tenant-scoped ones for a tenant admin. */
    public List<SecurityAdvisory> scoped(Scope scope) {
        return advisories.stream().filter(advisory -> advisory.scope() == scope).toList();
    }

    /** The tenant-scoped advisories concerning {@code tenant} - what that tenant's admins may see. */
    public List<SecurityAdvisory> forTenant(String tenant) {
        return advisories.stream()
                .filter(advisory -> advisory.scope() == Scope.TENANT && advisory.tenant().equals(tenant))
                .toList();
    }
}
