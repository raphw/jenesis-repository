/**
 * The default filesystem artifact-store backend: blobs under a mounted root directory, keyed by object path.
 * It {@code provides} an {@link build.jenesis.repository.store.ArtifactStoreProvider} answering to {@code filesystem},
 * discovered on the module path with {@code ServiceLoader} - the fallback {@code ArtifactStoreProvider.resolve}
 * selects when no other backend is named. Depends only on the store SPI and java.base.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.store.filesystem {
    requires build.jenesis.repository.store;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.filesystem.FilesystemArtifactStoreProvider;
}
