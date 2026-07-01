/**
 * The JFrog Artifactory import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.source.ImportSourceProvider} that builds an {@code ArtifactorySource} over the
 * storage API, reading the listing with Jackson. Depends on the import SPI, the format SPI (for the shared fetcher) and
 * Jackson; the server discovers it with {@code ServiceLoader}, so Artifactory support is present exactly when this
 * module is on the path.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.21 SHA-256/53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b
 * @jenesis.pin tools.jackson.core/jackson-core 3.1.4 SHA-256/3bda1cd6eff0a8d47bdfcaeae7c2bd5311d6c8ed494ef5f3e51029bb44aa9bdf
 * @jenesis.pin tools.jackson.core/jackson-databind 3.1.4 SHA-256/14034bfdf392b6ebec1b4bb6c1de29d604f0aa97251259a19d5f19af8719bb20
 */
module build.jenesis.repository.source.artifactory {
    requires build.jenesis.repository.source;
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.source.artifactory to build.jenesis.repository.test;
    provides build.jenesis.repository.source.ImportSourceProvider
            with build.jenesis.repository.source.artifactory.ArtifactorySourceProvider;
}
