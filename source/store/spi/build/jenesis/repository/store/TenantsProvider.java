package build.jenesis.repository.store;

import module java.base;

/**
 * A named factory for the {@link Tenants} directory, discovered at runtime with {@link ServiceLoader} - so how a
 * deployment keeps its tenant directory (a multi-tenant edition's store-backed one) is a drop-in module and the
 * composition names no implementation. Each provider reads its own configuration through the {@code config} lookup
 * (a property accessor returning {@code null} when unset); the deployment's root store is passed for a store-backed
 * directory, whose tenants are the top-level scopes of the shared {@code <tenant>/<repository>/...} layout. With no
 * module installed, {@link #resolve} answers the {@link Tenants#fixed fixed} directory over the configured tenant,
 * and {@link #installed()} is the capability signal a console or API gates its tenant management on.
 */
public interface TenantsProvider {

    /** The directory name this provider answers to, e.g. {@code store}. */
    String name();

    /** Build the directory over the deployment's root store, reading settings through {@code config}; empty when
     *  off. */
    Optional<Tenants> create(ArtifactStore root, UnaryOperator<String> config);

    /** Whether any tenants module is installed - the capability signal a console gates its tenant management on;
     *  without one the directory is the fixed single tenant and never grows. */
    static boolean installed() {
        return ServiceLoader.load(TenantsProvider.class).iterator().hasNext();
    }

    /** The first directory discovered via {@link ServiceLoader}, or the {@link Tenants#fixed fixed} directory over
     *  the configured {@code tenant} when no module is installed. */
    static Tenants resolve(ArtifactStore root, UnaryOperator<String> config, String tenant) {
        for (TenantsProvider provider : ServiceLoader.load(TenantsProvider.class)) {
            Optional<Tenants> tenants = provider.create(root, config);
            if (tenants.isPresent()) {
                return tenants.get();
            }
        }
        return Tenants.fixed(tenant);
    }
}
