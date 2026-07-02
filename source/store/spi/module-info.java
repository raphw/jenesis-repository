/**
 * The artifact-store SPI and the format-neutral content-addressed store ({@code Publication}) built on it.
 * No dependencies beyond java.base, so a format plugin builds on the store and its {@code Publication} without
 * pulling in the server. A backend ships as its own module that {@code provides} an {@code ArtifactStoreProvider},
 * discovered on the module path with {@code ServiceLoader}: the default filesystem backend, plus the optional s3
 * and azure backends when on the graph.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.store {
    exports build.jenesis.repository.store;
    uses build.jenesis.repository.store.ArtifactStoreProvider;
    uses build.jenesis.repository.store.PublishInterceptor;
}
