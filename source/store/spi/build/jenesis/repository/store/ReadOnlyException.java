package build.jenesis.repository.store;

/**
 * Thrown by {@link ReadOnlyArtifactStore} when a write is attempted against a deployment running in read-only mode
 * ({@code jenesis.repository.read-only=true}). Unchecked so it propagates through a format's write path without
 * widening every store signature - the same way {@link QuotaExceededException} does - and a server maps it to an
 * HTTP {@code 403 Forbidden}. The refusal is raised before the delegate is touched, so no bytes are ever stored.
 */
public final class ReadOnlyException extends RuntimeException {

    public ReadOnlyException() {
        super("This instance is in read-only mode: writes are refused");
    }
}
