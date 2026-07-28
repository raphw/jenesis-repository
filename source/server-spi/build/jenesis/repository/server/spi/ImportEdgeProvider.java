package build.jenesis.repository.server.spi;

import build.jenesis.repository.store.Features;

import module java.base;

/**
 * A free-core signal SPI through which a richer distribution claims ownership of the import edge - the repo-less
 * {@code POST /repository/admin/import} / {@code GET /repository/admin/import/<id>} surface the free
 * {@code ImportEdgeController} serves - discovered at runtime with {@link ServiceLoader}, exactly like the
 * {@link CapabilityContributor} SPI and the format / import-source plugins. When any provider is {@link #installed()
 * installed}, the free {@code ImportEdgeController} bean is simply not registered (see
 * {@code RepositoryAutoConfiguration}), so its mapping never joins the handler mapping and the distribution's own
 * import controller - the enterprise edition's tenant-scoped {@code /repository/<repo>/admin/import} with its audited,
 * SSRF-screened choreography - is the <em>only</em> import edge at boot.
 *
 * <p>This retires the cross-layer stopgap WFE.1 exists to remove: the enterprise edition previously dropped the free
 * import mapping with a {@code WebMvcRegistrations} bean (a bean/mapping override reaching across the free layer). With
 * this hook the enterprise instead ships an {@code ImportEdgeProvider} service - its mere presence on the module path
 * makes the free edge yield - and contributes its own controller bean, so free and enterprise contribute
 * <em>separate, non-colliding</em> controllers with no mapping-suppression bean and no endpoint-mapping collision.
 *
 * <p>With no provider installed (the free product) the free import edge is served exactly as before, byte-for-byte
 * unchanged - the same guarantee the {@link CapabilityContributor} zero-contributor case gives. Discovery honours the
 * shared {@link Features} enable/disable convention (a {@code jenesis.repository.<name>=false} switch and the
 * required-config self-disable), so a provider that is present but not configured for is inert, exactly as a missing
 * module would be.
 */
public interface ImportEdgeProvider {

    /** The distribution-owned import edge's feature name, e.g. {@code enterprise-import}. Toggled off with
     *  {@code jenesis.repository.<name>=false} through the shared {@link Features} convention, so a deployment can fall
     *  back to the free import edge without removing the module. */
    String name();

    /** The config keys this provider cannot run without; empty (the default) for one that claims the edge on presence
     *  alone. A provider whose required keys are unset {@link Features#active self-disables} at discovery, so the free
     *  edge is served until the distribution is configured for. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** Whether any {@link ServiceLoader}-discovered {@link ImportEdgeProvider} is active under the shared
     *  {@link Features} convention - the single question the free {@code RepositoryAutoConfiguration} asks to decide
     *  whether to register the free {@code ImportEdgeController}. {@code false} (no provider, or every discovered one
     *  configured off / missing its required config) means the free import edge is served; {@code true} means a
     *  distribution owns the import edge and the free controller yields, its mapping never registered. */
    static boolean installed() {
        for (ImportEdgeProvider provider : ServiceLoader.load(ImportEdgeProvider.class)) {
            if (Features.active(provider.name(), provider.requiredConfig())) {
                return true;
            }
        }
        return false;
    }
}
