package build.jenesis.repository.store;

/**
 * Thrown by {@link QuotaArtifactStore} when a write would store a new blob into a scope that is already at or over
 * its byte limit. Unchecked so it propagates through a format's write path without widening every store signature;
 * a server maps it to an HTTP {@code 507 Insufficient Storage}. The limit is checked before the blob is written, so
 * no partial bytes are stored when it is raised.
 */
public final class QuotaExceededException extends RuntimeException {

    private final long limit;
    private final long used;

    public QuotaExceededException(long limit, long used) {
        super("Storage quota exceeded: " + used + " of " + limit + " bytes already used");
        this.limit = limit;
        this.used = used;
    }

    public long limit() {
        return limit;
    }

    public long used() {
        return used;
    }
}
