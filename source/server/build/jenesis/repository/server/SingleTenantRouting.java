package build.jenesis.repository.server;

import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The free deployment's {@link RepositoryRouting}: every request addresses the one artifact space, so the route is the
 * whole {@link ArtifactStore} with no tenant or repository and the request path is offered to the formats unchanged.
 * This reproduces the headless single-tenant behaviour exactly; a multi-tenant edition replaces it by contributing its
 * own {@code RepositoryRouting} bean.
 */
public final class SingleTenantRouting implements RepositoryRouting {

    private final ArtifactStore store;

    public SingleTenantRouting(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public Route route(HttpServletRequest request) {
        return new Route(null, null, store, request.getRequestURI());
    }
}
