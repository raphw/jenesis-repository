/**
 * The JFrog Artifactory import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.source.ImportSourceProvider} that builds an {@code ArtifactorySource} over the
 * storage API. Depends only on the import SPI and the format SPI (for the shared fetcher); the server discovers it with
 * {@code ServiceLoader}, so Artifactory support is present exactly when this module is on the path.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.source.artifactory {
    requires build.jenesis.repository.source;
    requires build.jenesis.repository.format;
    exports build.jenesis.repository.source.artifactory to build.jenesis.repository.test;
    provides build.jenesis.repository.source.ImportSourceProvider
            with build.jenesis.repository.source.artifactory.ArtifactorySourceProvider;
}
