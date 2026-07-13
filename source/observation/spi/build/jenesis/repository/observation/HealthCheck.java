package build.jenesis.repository.observation;

import module java.base;

/**
 * One self-describing health check a plugin reports: a stable {@code jenesis.<feature>.<check>} {@code name}, a
 * human-readable {@code description} (the same text Actuator and the console overview show, so the check explains
 * itself), a {@link Health} {@code status} and an optional plain-text {@code detail} explaining a degraded/down state
 * (never a secret). Immutable; the name is validated at construction against {@link Signals}, so a check that breaks the
 * grammar fails when it is built, not when it is scraped.
 */
public record HealthCheck(String name, String description, Health status, String detail) {

    public HealthCheck {
        Signals.require(name);
        description = Objects.requireNonNull(description, "description");
        status = Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    /** An {@link Health#UP} check. */
    public static HealthCheck up(String name, String description) {
        return new HealthCheck(name, description, Health.UP, "");
    }

    /** A check reporting {@code status} with a plain-text {@code detail}. */
    public static HealthCheck of(String name, String description, Health status, String detail) {
        return new HealthCheck(name, description, status, detail);
    }
}
