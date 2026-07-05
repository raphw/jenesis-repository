package build.jenesis.repository.server;

import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;

/**
 * The seam that resolves an incoming request to the artifact space and format path the {@link FormatDispatcher}
 * serves it against, so one shared {@link RepositoryController} drives both the fixed-tenant deployment and a
 * multi-tenant one without a fork. Every deployment shares one store layout, {@code <tenant>/<repository>/...}:
 * a route always names its tenant and repository and always carries the doubly
 * {@link ArtifactStore#scope(String) scoped} store ({@code root.scope(tenant).scope(repository)}), so switching a
 * deployment between fixed- and multi-tenant routing is a configuration change that finds the data where it was
 * left. By default the {@link FixedTenantRouting} binds: every request resolves to the configured
 * {@code jenesis.repository.tenant} / {@code jenesis.repository.repository} space (each {@code default} by
 * default) with the request path unchanged beyond the {@code /repository} prefix strip. A multi-tenant deployment
 * contributes its own {@code RepositoryRouting} bean (overriding the {@code @ConditionalOnMissingBean} default)
 * that reads the tenant from the {@code Jenesis-Repository-Key} header and the repository from the first path
 * segment, and strips the {@code /<repo>} prefix from the path a format sees.
 */
public interface RepositoryRouting {

    /** Resolve the request to a {@link Route}; never {@code null}. */
    Route route(HttpServletRequest request);

    /**
     * The resolved artifact space for a request: the {@code tenant} and {@code repository} it addresses (never
     * {@code null} - the fixed-tenant deployment resolves its configured defaults), the doubly-scoped
     * {@code root.scope(tenant).scope(repository)} {@link ArtifactStore} the format reads and writes, and the
     * {@code path} the format matches on (the request URI with the {@code /repository} prefix stripped on the
     * fixed-tenant deployment, the repository-prefix-stripped path under multi-tenant routing).
     */
    record Route(String tenant, String repository, ArtifactStore store, String path) {

        public Route {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(path, "path");
        }
    }
}
