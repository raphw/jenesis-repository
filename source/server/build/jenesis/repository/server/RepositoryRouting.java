package build.jenesis.repository.server;

import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The seam that resolves an incoming request to the artifact space and format path the {@link FormatDispatcher}
 * serves it against, so one shared {@link RepositoryController} drives both the single-tenant deployment and a
 * multi-tenant one without a fork. By default the {@link SingleTenantRouting} binds: no tenant, the whole
 * store, and the request path unchanged. A multi-tenant deployment contributes its own {@code RepositoryRouting} bean
 * (overriding the {@code @ConditionalOnMissingBean} default) that reads the tenant from the {@code Jenesis-Repository-Key}
 * header and the repository from the first path segment, returns the tenant-and-repository {@link ArtifactStore#scope
 * scoped} store, and strips the {@code /<repo>} prefix from the path a format sees. This keeps the reusable shape
 * minimal and single-tenant by default.
 */
public interface RepositoryRouting {

    /** Resolve the request to a {@link Route}; never {@code null}. */
    Route route(HttpServletRequest request);

    /**
     * The resolved artifact space for a request: the {@code tenant} and {@code repository} it addresses (both
     * {@code null} on the single-tenant deployment), the already-scoped {@link ArtifactStore} the format reads and
     * writes, and the {@code path} the format matches on (the full request URI on the single-tenant deployment, the
     * repository-prefix-stripped path under multi-tenant routing).
     */
    record Route(String tenant, String repository, ArtifactStore store, String path) {
    }
}
