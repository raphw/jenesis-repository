package build.jenesis.repository.posture;

import module java.base;

/**
 * The seam a module reports its security-posture advisories through: given the effective {@link Configuration}, it
 * returns zero or more {@link SecurityAdvisory security advisories} about potentially-unsafe settings it owns. A module
 * (or its provider) implements this and is discovered with {@link ServiceLoader}; a <em>disabled or absent</em> module
 * returns nothing, so the console never advises about a feature that is not running - the same graceful-degradation rule
 * the observation seam follows.
 *
 * <p>Thin core: a module owns the advisories about <em>its own</em> settings; only the deployment-cross-cutting ones
 * (auth off, the dev profile, an exposed management port, no rate limit) are seeded centrally by
 * {@link SecurityPosture}. An advisor is a pure function of configuration - it holds no mutable state and never mutates
 * anything (observing posture never changes it), so it is safe to discover, cache and call on any thread.
 */
@FunctionalInterface
public interface SafetyAdvisor {

    /** The advisories this module raises against {@code config}; empty (never {@code null}) when nothing is unsafe. */
    List<SecurityAdvisory> advise(Configuration config);
}
