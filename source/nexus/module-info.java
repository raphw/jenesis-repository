/**
 * The Sonatype Nexus 3 import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.source.ImportSourceProvider} that builds a {@code NexusSource} over the components
 * REST API. Depends only on the import SPI and the format SPI (for the shared fetcher); the server discovers it with
 * {@code ServiceLoader}, so Nexus support is present exactly when this module is on the path.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.source.nexus {
    requires build.jenesis.repository.source;
    requires build.jenesis.repository.format;
    exports build.jenesis.repository.source.nexus to build.jenesis.repository.test;
    provides build.jenesis.repository.source.ImportSourceProvider
            with build.jenesis.repository.source.nexus.NexusSourceProvider;
}
