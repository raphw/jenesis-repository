package build.jenesis.repository.posture;

/**
 * Who a {@link SecurityAdvisory} concerns, so the console shows it to the right audience: a {@link #DEPLOYMENT}-wide
 * advisory (auth off, the dev profile active, an exposed management port) is the operator's / superadmin's to fix and
 * is shown to them; a {@link #TENANT}-scoped one (a per-tenant credential-lifetime or webhook-secret footgun) belongs
 * to that tenant's admins and carries the tenant it concerns. Deployment-wide is the default: a core seed reads only
 * deployment configuration, so it is never accidentally leaked to a tenant admin who cannot act on it.
 */
public enum Scope {

    /** Concerns the whole deployment - the operator's / superadmin's to fix. */
    DEPLOYMENT,
    /** Concerns a single tenant - that tenant's admins' to fix. */
    TENANT
}
