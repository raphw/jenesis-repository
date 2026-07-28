/**
 * The artifact-store SPI and the format-neutral content-addressed store ({@code Publication}) built on it.
 * Its only dependency beyond java.base is the equally minimal, registry-free
 * {@code build.jenesis.repository.observation} SPI (so a capped store can report its {@code jenesis.quota.*}
 * used-vs-available signals), so a format plugin builds on the store and its {@code Publication} without
 * pulling in the server. A backend ships as its own module that {@code provides} an {@code ArtifactStoreProvider},
 * discovered on the module path with {@code ServiceLoader}: the default filesystem backend, plus the optional s3,
 * gcs and azure backends when on the graph. The {@code Tenants} directory of the shared
 * {@code <tenant>/<repository>/...} layout is discovered the same way ({@code TenantsProvider}); with no module
 * installed it is the fixed single tenant. A publication carries one discovered hook class, the
 * {@code PublicationObserver} after-commit observer (forwarding, webhooks, replication - notified only once an
 * accepted artifact serves, contained so it never fails a publish), with the verdict-bearing
 * {@code PublishInterceptor} (accept / quarantine / reject before the pointer links, withhold on read) as its
 * {@code instanceof}-detected sub-interface: one {@code uses PublicationObserver} clause discovers both, and
 * {@code Publication} splits the discovered list to drive the interceptor chain while notifying every observer.
 * Both are empty by default.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.store {
    requires build.jenesis.repository.observation;
    requires org.slf4j;
    exports build.jenesis.repository.store;
    uses build.jenesis.repository.store.ArtifactStoreProvider;
    uses build.jenesis.repository.store.PublicationObserver;
    uses build.jenesis.repository.store.TenantsProvider;
}
