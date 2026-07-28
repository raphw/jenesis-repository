package build.jenesis.repository.posture;

import module java.base;

/**
 * The core security-posture seeder: the {@link SafetyAdvisor} that owns the <em>deployment-cross-cutting</em>
 * advisories - the ones that belong to no single feature module because they are properties of the whole deployment
 * (authorization off, the dev profile active, an SSRF allowlist disabled, no rate limit, a wide-open console, a writable
 * public demo). A feature module owns the advisories about its own settings; this seeds only the shared ones, each
 * grounded in a <em>real</em> {@code jenesis.*} (or Spring) key an operator can actually set, so the fix is copy-and-go.
 *
 * <p>It is {@code provides}-declared in this module's descriptor, so it is discovered automatically wherever the posture
 * module is on the graph. It holds no state and reads configuration only to decide <em>whether</em> a condition holds -
 * an advisory's text never repeats a read value, so this surface cannot leak a secret.
 */
public final class SecurityPosture implements SafetyAdvisor {

    /** Where the reference documents each advisory's condition and fix (W10.0); each advisory anchors on its id. */
    static final String DOCS = "https://jenesis.build/docs/security/posture";

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        List<SecurityAdvisory> advisories = new ArrayList<>();

        // 1. Per-credential authorization disabled: the instance serves every request anonymously. This is the single
        //    source of truth for the boot "running ANONYMOUS/OPEN" WARN the server used to log ad hoc.
        if (!config.flag("jenesis.repository.auth", true)) {
            advisories.add(SecurityAdvisory.deployment("jenesis.auth.open", Severity.CRITICAL,
                    "Authorization is disabled - the instance is fully open",
                    "jenesis.repository.auth=false serves every request anonymously: anyone on the network can read, "
                            + "publish, delete and administer artifacts with no credential.",
                    "Enforce per-credential authorization and grant each client only the repositories and actions it "
                            + "needs. Anonymous read is fine for a public mirror, but keep writes and admin key-gated.",
                    "jenesis.repository.auth", "true", DOCS + "#jenesis.auth.open"));
        }

        // 2. Private-host import SSRF screen disabled: an import URL can then reach internal addresses (link-local,
        //    RFC1918, loopback) and turn the importer into a request proxy into the deployment's own network.
        if (!config.flag("jenesis.repository.block-private-import-hosts", true)) {
            advisories.add(SecurityAdvisory.deployment("jenesis.importer.ssrf", Severity.WARN,
                    "Import SSRF screen is disabled",
                    "jenesis.repository.block-private-import-hosts=false lets an import fetch from private/loopback "
                            + "addresses, so a caller can use the importer to reach internal services (an SSRF pivot) - "
                            + "especially dangerous on a multi-tenant or anonymous instance.",
                    "Keep the private-host screen on; open it only for a controlled internal-host migration and close "
                            + "it again afterwards.",
                    "jenesis.repository.block-private-import-hosts", "true", DOCS + "#jenesis.importer.ssrf"));
        }

        // 3. No rate limit: a public instance with no throttle is trivially exhausted by a single abusive client.
        if (config.number("jenesis.repository.rate-limit", 0) <= 0) {
            advisories.add(SecurityAdvisory.deployment("jenesis.ratelimit.unset", Severity.WARN,
                    "No request rate limit is configured",
                    "jenesis.repository.rate-limit is unset (0 = unlimited), so a public instance has no per-credential "
                            + "throttle and a single client can saturate it (a brute-force or denial-of-service vector).",
                    "Set a sensible per-credential request-per-second ceiling; a small limit stops abuse while leaving "
                            + "normal build traffic untouched.",
                    "jenesis.repository.rate-limit", "600", DOCS + "#jenesis.ratelimit.unset"));
        }

        // 4. Wildcard console admins: '*' makes every authenticated user a console admin (the explicit open-console
        //    opt-out) instead of naming the operators who should hold admin.
        if ("*".equals(config.optional("jenesis.ui.admins").orElse(""))) {
            advisories.add(SecurityAdvisory.deployment("jenesis.console.wildcard", Severity.WARN,
                    "The admin console grants admin to every signed-in user",
                    "jenesis.ui.admins=* makes every authenticated user a console admin, so anyone who can sign in can "
                            + "administer the deployment - the open-console opt-out, rarely what production wants.",
                    "Name the specific admin principals (provider-qualified ids, e.g. github/<id> or oidc/<sub>) rather "
                            + "than the '*' wildcard, so only your operators hold admin.",
                    "jenesis.ui.admins", "github/<your-id>", DOCS + "#jenesis.console.wildcard"));
        }

        // 5. The dev security profile active: DevSecurityConfig replaces the production chain with a permissive
        //    local-only one (form login, in-memory users). Never intended outside a developer laptop.
        if (springProfiles(config).contains("dev")) {
            advisories.add(SecurityAdvisory.deployment("jenesis.profile.dev", Severity.CRITICAL,
                    "The 'dev' security profile is active",
                    "spring.profiles.active includes 'dev', so the console runs its local-only development security "
                            + "(permissive form login with in-memory users) instead of the production OAuth2/OIDC chain "
                            + "- an authentication bypass anywhere but a developer's machine.",
                    "Remove 'dev' from the active profiles in any shared or production deployment and configure a real "
                            + "login provider (OIDC/SSO or GitHub).",
                    "spring.profiles.active", "<remove dev>", DOCS + "#jenesis.profile.dev"));
        }

        // 6. A writable demo: the demo seeding is on but the instance is not read-only, so a public demo anyone can
        //    browse is also one anyone can write to.
        if (config.flag("jenesis.repository.demo", false) && !config.flag("jenesis.repository.read-only", false)) {
            advisories.add(SecurityAdvisory.deployment("jenesis.demo.writable", Severity.WARN,
                    "The demo instance is writable",
                    "jenesis.repository.demo=true seeds a public demo, but jenesis.repository.read-only is off, so the "
                            + "browsable demo also accepts writes - visitors can publish to or mutate it.",
                    "Turn on read-only mode for a demo/mirror so the seeded content is browsable but immutable; the "
                            + "demo seeding runs before the write-gate.",
                    "jenesis.repository.read-only", "true", DOCS + "#jenesis.demo.writable"));
        }

        return advisories;
    }

    /** The active Spring profiles as a lowercase set, read from {@code spring.profiles.active} (comma-separated). */
    private static Set<String> springProfiles(Configuration config) {
        return config.optional("spring.profiles.active")
                .map(value -> Arrays.stream(value.split(","))
                        .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                        .filter(profile -> !profile.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
    }
}
