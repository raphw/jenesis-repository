package build.jenesis.repository.posture;

import module java.base;

/**
 * One self-describing security-posture advisory: a potentially-unsafe configuration a module wants an operator to know
 * about. It carries a stable {@link #id} ({@code jenesis.<feature>.<signal>}, the {@link Advisories} grammar), a
 * {@link #severity}, a {@link #scope} (deployment-wide vs a named {@link #tenant}), a short {@link #title}, a plain
 * {@link #why} it is unsafe, a suggested safer {@link #fix} (best practice in prose), the <em>exact</em> setting to
 * change ({@link #settingKey} / {@link #settingValue}, so an operator can copy it straight into config) and a
 * {@link #docs} link. Immutable; the id is validated at construction against {@link Advisories}.
 *
 * <p><strong>The advisory names the risk, never the secret.</strong> An advisor decides whether to raise an advisory by
 * reading configuration, but the text here describes the condition and the fix - it never embeds a credential, a key or
 * any read secret value, so the posture surface (which enumerates a deployment's weaknesses) cannot itself leak one.
 */
public record SecurityAdvisory(String id, Severity severity, Scope scope, String tenant, String title, String why,
                               String fix, String settingKey, String settingValue, String docs) {

    public SecurityAdvisory {
        Advisories.require(id);
        severity = Objects.requireNonNull(severity, "severity");
        scope = Objects.requireNonNull(scope, "scope");
        tenant = tenant == null ? "" : tenant;
        title = Objects.requireNonNull(title, "title");
        why = Objects.requireNonNull(why, "why");
        fix = Objects.requireNonNull(fix, "fix");
        settingKey = settingKey == null ? "" : settingKey;
        settingValue = settingValue == null ? "" : settingValue;
        docs = docs == null ? "" : docs;
        if (scope == Scope.TENANT && tenant.isBlank()) {
            throw new IllegalArgumentException("A tenant-scoped advisory must name its tenant: " + id);
        }
        if (scope == Scope.DEPLOYMENT && !tenant.isBlank()) {
            throw new IllegalArgumentException("A deployment-wide advisory must not name a tenant: " + id);
        }
    }

    /** A deployment-wide advisory (the common case for a core seed). */
    public static SecurityAdvisory deployment(String id, Severity severity, String title, String why, String fix,
                                              String settingKey, String settingValue, String docs) {
        return new SecurityAdvisory(id, severity, Scope.DEPLOYMENT, "", title, why, fix, settingKey, settingValue, docs);
    }

    /** A tenant-scoped advisory, concerning {@code tenant}. */
    public static SecurityAdvisory tenant(String id, Severity severity, String tenant, String title, String why,
                                          String fix, String settingKey, String settingValue, String docs) {
        return new SecurityAdvisory(id, severity, Scope.TENANT, tenant, title, why, fix, settingKey, settingValue, docs);
    }
}
