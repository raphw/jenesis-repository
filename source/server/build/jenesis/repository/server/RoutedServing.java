package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;

import java.io.IOException;

/**
 * The seam that lets a deployment serve a <em>routed</em> repository - one defined as a read-through proxy of an
 * upstream or a group view over other repositories - across its backings on a read, rather than only over its own
 * hosted store. The free {@link RepositoryController} consults it on every {@code GET}/{@code HEAD}: a repository
 * that {@link #routes(String) has a routed definition} is served here (a proxy pulls through its own upstream on a
 * local miss and caches per its definition, a group consults its members in order with the first hit winning, a
 * {@code nocache} leg stays a pure view), while a plain hosted repository is left to the {@link FormatDispatcher}
 * so it keeps the deployment-wide, format-level pull-through ({@code format-upstream.<format>}). Writes are not
 * routed here - a routed group deploy already lands in its push-target member on the write path - so this only
 * governs reads.
 *
 * <p>The read-side quarantine guard ({@link build.jenesis.repository.store.PublishInterceptor#withheld}) still bites:
 * a routed hosted/group leg serves through the format's own {@link RepositoryFormat#handle handle}, and a routed
 * proxy leg screens each fetched artifact through the same compliance gate, so a withheld or gate-denied path stays a
 * {@code 404} on a routed read exactly as on a direct one.
 *
 * <p>A deployment with no routed repositories binds {@link #NONE}, so the free single-tenant edition serves every
 * repository over its own store unchanged. The seam holds no framework type; an embedder contributes it as a bean
 * (the free auto-configuration's {@code @ConditionalOnMissingBean} default is {@code NONE}).
 */
public interface RoutedServing {

    /**
     * Whether {@code repository} has a routed definition (a proxy or a group) that {@link #serve} must drive, as
     * opposed to a plain hosted repository the caller dispatches over its own store. Kept a cheap predicate so the
     * hot read path pays only a definition lookup for the common hosted case, never a format resolution.
     */
    boolean routes(String repository);

    /**
     * Serve a read for {@code repository} in {@code tenant} across its routed backings through {@code exchange},
     * using {@code format} for the hosted and proxy legs (a group's members share the request's format). Called only
     * when {@link #routes(String)} is {@code true}; the exchange always carries a response afterwards (a served
     * artifact, or a {@code 404} when a proxy misses and a group exhausts its members).
     */
    void serve(String tenant, String repository, RepositoryFormat format, FormatExchange exchange) throws IOException;

    /** The no-routing sentinel: every repository is plain hosted, so the controller always dispatches normally. */
    RoutedServing NONE = new RoutedServing() {
        @Override
        public boolean routes(String repository) {
            return false;
        }

        @Override
        public void serve(String tenant, String repository, RepositoryFormat format, FormatExchange exchange) {
        }
    };
}
