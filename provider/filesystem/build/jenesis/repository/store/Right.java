package build.jenesis.repository.store;

/**
 * A right a credential can carry, named by the surface it acts on and the verb it permits, so one key model can
 * span the surfaces a deployment exposes without a project on one surface being confused for one of the same name
 * on another. The verbs are uniform {@code read} / {@code write}.
 *
 * A grant lists rights by their wire token: an exact {@code <surface>:<verb>} (for example
 * {@code repository:write}), a per-surface {@code <surface>:*}, or {@code *} for every privilege - the way to hand
 * a key the full set in one token.
 */
public enum Right {

    CACHE_READ("cache", "read"),

    CACHE_WRITE("cache", "write"),

    REPOSITORY_READ("repository", "read"),

    REPOSITORY_WRITE("repository", "write");

    private final String surface;
    private final String verb;

    Right(String surface, String verb) {
        this.surface = surface;
        this.verb = verb;
    }

    public String surface() {
        return surface;
    }

    public String verb() {
        return verb;
    }

    /** The wire form carried in a grant, {@code <surface>:<verb>}. */
    public String token() {
        return surface + ":" + verb;
    }

    /**
     * Whether a grant token confers this right: the exact {@code <surface>:<verb>}, the per-surface wildcard
     * {@code <surface>:*}, or the all-privileges {@code *}. An unknown token confers nothing.
     */
    public boolean grantedBy(String token) {
        String trimmed = token.trim();
        return trimmed.equals("*")
                || trimmed.equals(surface + ":*")
                || trimmed.equals(surface + ":" + verb);
    }
}
