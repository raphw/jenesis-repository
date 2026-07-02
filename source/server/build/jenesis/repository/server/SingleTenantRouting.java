package build.jenesis.repository.server;

import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The free deployment's {@link RepositoryRouting}: every request addresses the one artifact space, so the route is the
 * whole {@link ArtifactStore} with no tenant or repository. Artifacts are served under the {@code /repository/} prefix,
 * which is stripped so a format sees its own {@code /maven/}, {@code /raw/} ... path; the OCI {@code /v2/} registry,
 * which the Docker protocol pins at the host root, is offered unchanged. This reproduces the headless single-tenant
 * behaviour; a multi-tenant edition replaces it by contributing its own {@code RepositoryRouting} bean.
 */
public final class SingleTenantRouting implements RepositoryRouting {

    private static final String PREFIX = "/repository";

    private final ArtifactStore store;

    public SingleTenantRouting(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public Route route(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String path = uri.equals(PREFIX) || uri.startsWith(PREFIX + "/") ? uri.substring(PREFIX.length()) : uri;
        return new Route(null, null, store, path.isEmpty() ? "/" : path);
    }
}
