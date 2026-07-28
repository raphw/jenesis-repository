package build.jenesis.repository.posture;

import module java.base;

/**
 * The effective deployment configuration a {@link SafetyAdvisor} reads to decide whether a condition holds - a
 * registry-free, {@code java.base} view of the same {@code jenesis.*} key space the settings use, so an advisor never
 * touches Spring. The distribution installs the real lookup by wrapping the Spring {@code Environment}
 * ({@code Configuration.of(environment::getProperty)}), so every key is resolvable in its relaxed environment-variable
 * spelling too; a test builds one from a map. The single abstract method is {@link #value}; the typed helpers
 * ({@link #isSet}, {@link #flag}, {@link #number}) read through it so an advisor stays terse.
 *
 * <p>An advisor reads a key's value only to <em>decide whether to advise</em> - it never copies a secret into an
 * advisory's text (a {@code SecurityAdvisory} names the risk, never the value).
 */
@FunctionalInterface
public interface Configuration {

    /** The effective value of {@code key}, or {@code null} when it is unset. */
    String value(String key);

    /** The value of {@code key}, or {@code null} - as an {@link Optional}, empty when unset or blank. */
    default Optional<String> optional(String key) {
        String value = value(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /** Whether {@code key} is set to a non-blank value. */
    default boolean isSet(String key) {
        return optional(key).isPresent();
    }

    /** {@code key} parsed as a boolean, or {@code defaultValue} when unset - the relaxed-binding rule the settings use
     *  ({@code true} only for a literal {@code true}, everything else, including a blank, is {@code false}). */
    default boolean flag(String key, boolean defaultValue) {
        Optional<String> value = optional(key);
        return value.map(v -> v.equalsIgnoreCase("true")).orElse(defaultValue);
    }

    /** {@code key} parsed as a long, or {@code defaultValue} when unset or unparseable. */
    default long number(String key, long defaultValue) {
        Optional<String> value = optional(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.get());
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }

    /** A configuration over a plain {@code key -> value} lookup - the distribution hands in {@code
     *  environment::getProperty}. */
    static Configuration of(UnaryOperator<String> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return lookup::apply;
    }

    /** A configuration over a fixed map - the {@code java.base} form a test builds without a Spring context. */
    static Configuration ofMap(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return copy::get;
    }
}
