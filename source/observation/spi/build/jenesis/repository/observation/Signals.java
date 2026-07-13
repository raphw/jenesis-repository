package build.jenesis.repository.observation;

import module java.base;

/**
 * The naming grammar every observability signal shares, kept in one place so a health check, a metric and a
 * background-task status all read like the configuration keys beside them. A signal name is
 * {@code jenesis.<feature>.<signal...>} - the same {@code jenesis.<feature>.*} convention the settings use - so a metric
 * called {@code jenesis.gc.reclaimed.bytes} lines up with the {@code jenesis.repository.gc} feature it belongs to and is
 * discoverable from either side. This is the checkable, {@code java.base} form of the grammar {@code OBSERVABILITY.md}
 * documents (and the Micrometer meter-name guard enforces at the registry): a name that breaks it is rejected at
 * construction, not at scrape time, because a signal name is a build-time constant - a broken one is a bug to fail on,
 * never a string to sanitise.
 */
public final class Signals {

    /** The full grammar: dot-separated lowercase segments under the {@code jenesis} root. */
    public static final Pattern NAME = Pattern.compile("^jenesis(\\.[a-z][a-z0-9]*)+$");

    private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9]*");

    private Signals() {
    }

    /**
     * Compose a signal name {@code jenesis.<feature>.<segments...>} from a feature and one or more trailing segments,
     * validating each against the grammar. Throws {@link IllegalArgumentException} when a segment is null, empty or not
     * lowercase {@code [a-z][a-z0-9]*}.
     */
    public static String name(String feature, String... segments) {
        StringBuilder builder = new StringBuilder("jenesis.").append(segment(feature));
        for (String segment : segments) {
            builder.append('.').append(segment(segment));
        }
        return builder.toString();
    }

    private static String segment(String segment) {
        if (segment == null || !SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException("Not a lowercase [a-z][a-z0-9]* signal segment: " + segment);
        }
        return segment;
    }

    /** Whether {@code name} is a well-formed signal name. */
    public static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Return {@code name} when well-formed, else throw {@link IllegalArgumentException} - the guard a descriptor runs
     *  in its constructor. */
    public static String require(String name) {
        if (!valid(name)) {
            throw new IllegalArgumentException("Not a jenesis.<feature>.<signal> name: " + name);
        }
        return name;
    }
}
