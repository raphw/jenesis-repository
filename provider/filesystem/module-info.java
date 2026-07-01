/**
 * The artifact-store SPI, the format-neutral content-addressed store ({@code Publication}) built on it, and the
 * default filesystem backend. No dependencies beyond java.base, so a format plugin builds on the store and its
 * {@code Publication} without pulling in the server. The provider is exposed through the module descriptor (provides),
 * which is how ServiceLoader discovers it on the module path; the s3 and azure backend modules contribute further
 * providers when on the graph.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.store {
    exports build.jenesis.repository.store;
    uses build.jenesis.repository.store.ArtifactStoreProvider;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.FilesystemArtifactStoreProvider;
}
