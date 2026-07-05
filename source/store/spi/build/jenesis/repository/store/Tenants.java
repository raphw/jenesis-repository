package build.jenesis.repository.store;

import module java.base;

/**
 * The tenant directory of a deployment: which top-level tenants exist in the shared
 * {@code <tenant>/<repository>/...} store layout, and the lifecycle to add one. How the directory is kept is the
 * implementation's part, supplied by a {@link TenantsProvider} module discovered with
 * {@link java.util.ServiceLoader} (a multi-tenant edition backs it with the store, its tenants the top-level
 * scopes); with none installed the {@link #fixed} directory stands in - the free deployment's one configured
 * tenant - so listing and existence always answer, and a console or API offers tenant management only when
 * {@link TenantsProvider#installed()} says the capability is there.
 */
public interface Tenants {

    /** The tenants of this deployment, sorted; never empty - a fixed-tenant deployment answers its one tenant. */
    List<String> list() throws IOException;

    /** Whether {@code tenant} is in the directory. */
    boolean exists(String tenant) throws IOException;

    /** Add {@code tenant} to the directory, so its {@code <tenant>/<repository>/...} spaces can be addressed. */
    void create(String tenant) throws IOException;

    /**
     * The directory standing in when no tenants module is installed: exactly the one configured {@code tenant} of
     * the fixed-tenant deployment. It cannot grow - {@link #create} refuses - which a surface never reaches when it
     * gates tenant management on {@link TenantsProvider#installed()}.
     */
    static Tenants fixed(String tenant) {
        Objects.requireNonNull(tenant, "tenant");
        return new Tenants() {

            @Override
            public List<String> list() {
                return List.of(tenant);
            }

            @Override
            public boolean exists(String candidate) {
                return tenant.equals(candidate);
            }

            @Override
            public void create(String candidate) {
                throw new UnsupportedOperationException("A fixed-tenant deployment has exactly one tenant ('"
                        + tenant + "'); install a tenants module to manage more");
            }
        };
    }
}
