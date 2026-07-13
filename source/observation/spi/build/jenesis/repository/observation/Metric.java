package build.jenesis.repository.observation;

import module java.base;

/**
 * One self-describing metric a plugin reports: a stable {@code jenesis.<feature>.<signal>} {@code name}, a
 * human-readable {@code description}, its {@link Kind} (a monotonic {@code COUNTER} or a point-in-time {@code GAUGE}),
 * the current {@code value}, an optional {@code limit} (the ceiling the value is measured against, where the source
 * exposes one - quota bytes available, a rate-limit budget, a cache capacity) and a {@code unit} ({@code "bytes"},
 * {@code "requests"}, {@code ""} for a bare count). The optional limit is what lets the console show <em>data used vs
 * available</em> and <em>how close to the limit</em> without the reporter pre-computing a percentage. Immutable; the
 * name is validated at construction against {@link Signals}.
 */
public record Metric(String name, String description, Kind kind, double value, OptionalDouble limit, String unit) {

    /** Whether the metric only ever increases (a {@code COUNTER}) or reads a current level (a {@code GAUGE}). */
    public enum Kind { COUNTER, GAUGE }

    public Metric {
        Signals.require(name);
        description = Objects.requireNonNull(description, "description");
        kind = Objects.requireNonNull(kind, "kind");
        limit = limit == null ? OptionalDouble.empty() : limit;
        unit = unit == null ? "" : unit;
    }

    /** A monotonic counter with no ceiling. */
    public static Metric counter(String name, String description, double value, String unit) {
        return new Metric(name, description, Kind.COUNTER, value, OptionalDouble.empty(), unit);
    }

    /** A point-in-time gauge with no ceiling. */
    public static Metric gauge(String name, String description, double value, String unit) {
        return new Metric(name, description, Kind.GAUGE, value, OptionalDouble.empty(), unit);
    }

    /** A gauge measured against a {@code limit} - data used vs available. */
    public static Metric bounded(String name, String description, double used, double limit, String unit) {
        return new Metric(name, description, Kind.GAUGE, used, OptionalDouble.of(limit), unit);
    }

    /** The fraction of the limit the value occupies ({@code 0..1+}) - how close to the ceiling the signal is; empty
     *  when there is no limit or a non-positive one. */
    public OptionalDouble usage() {
        return limit.isPresent() && limit.getAsDouble() > 0
                ? OptionalDouble.of(value / limit.getAsDouble())
                : OptionalDouble.empty();
    }
}
