/**
 * The security-posture SPI (WO.5): the self-describing <em>configuration warnings</em> a plugin reports about the
 * effective deployment configuration - each a {@link build.jenesis.repository.posture.SecurityAdvisory} carrying a
 * stable {@code jenesis.<feature>.<signal>} id (the {@link build.jenesis.repository.posture.Advisories} grammar, the
 * same {@code jenesis.<feature>.*} convention configuration and observation use), a
 * {@link build.jenesis.repository.posture.Severity severity}, a {@link build.jenesis.repository.posture.Scope scope}
 * (deployment-wide vs per-tenant), a plain <em>why-this-is-unsafe</em>, a suggested safer alternative (the best-practice
 * fix plus the exact {@code jenesis.*} key/value) and a docs link. A module implements
 * {@link build.jenesis.repository.posture.SafetyAdvisor} through the same optional-method pattern the observation seam
 * uses, is discovered with {@link java.util.ServiceLoader}, and is handed the effective
 * {@link build.jenesis.repository.posture.Configuration}; a <em>disabled or absent</em> module contributes nothing, so
 * the console never advises about something that is not running.
 *
 * <p>This is deliberately <strong>not</strong> the {@code capabilities.advisories()} seam (which reports CVE /
 * vulnerability advisories about published artifacts) - it reports the deployment's own configuration posture. The
 * module is registry-free and {@code java.base}-only, so every format / compliance / storage / maintenance SPI can
 * {@code requires} it without dragging in Spring; the distribution collects the sources into one
 * {@link build.jenesis.repository.posture.PostureReport}, logs the deployment-wide advisories once at boot and surfaces
 * the same list on the console and an admin API - one source of truth, many consumers. Core owns only the
 * deployment-cross-cutting advisories ({@link build.jenesis.repository.posture.SecurityPosture}); a feature owns its
 * own.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.posture {
    exports build.jenesis.repository.posture;
    uses build.jenesis.repository.posture.SafetyAdvisor;
    provides build.jenesis.repository.posture.SafetyAdvisor
            with build.jenesis.repository.posture.SecurityPosture;
}
