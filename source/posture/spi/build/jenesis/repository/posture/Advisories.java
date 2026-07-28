package build.jenesis.repository.posture;

import module java.base;

/**
 * The naming grammar every security-posture advisory id shares - the same {@code jenesis.<feature>.<signal>} convention
 * the {@code configuration} keys and the observation {@code Signals} use, kept in one place so an advisory id reads like
 * the setting it is about ({@code jenesis.auth.open} sits beside {@code jenesis.repository.auth}). An id is validated at
 * construction ({@link SecurityAdvisory} calls {@link #require}), not when it is rendered, because an id is a build-time
 * constant - a broken one is a bug to fail on, never a string to sanitise.
 */
public final class Advisories {

    /** The full grammar: dot-separated lowercase segments under the {@code jenesis} root. */
    public static final Pattern ID = Pattern.compile("^jenesis(\\.[a-z][a-z0-9]*)+$");

    private Advisories() {
    }

    /** Whether {@code id} is a well-formed advisory id. */
    public static boolean valid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Return {@code id} when well-formed, else throw {@link IllegalArgumentException} - the guard an advisory runs in
     *  its constructor. */
    public static String require(String id) {
        if (!valid(id)) {
            throw new IllegalArgumentException("Not a jenesis.<feature>.<signal> advisory id: " + id);
        }
        return id;
    }
}
