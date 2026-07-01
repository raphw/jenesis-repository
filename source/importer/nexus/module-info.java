/**
 * The Sonatype Nexus 3 import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} that builds a {@code NexusSource} over the components
 * REST API, reading the listing responses with Jackson. Depends on the import SPI, the format SPI (for the shared
 * fetcher) and Jackson; the server discovers it with {@code ServiceLoader}, so Nexus support is present exactly when
 * this module is on the path.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.0 SHA-256/5e353ce53c6901105dfcbf183e3220c17072e334e552b818a4bb1b99decea596
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.0 SHA-256/3ef94a3dddeafc247c50230fad0315981b2ce4ae6e91cfb4368a86f328904e4f
 * @jenesis.pin tools.jackson.databind 3.2.0
 */
module build.jenesis.repository.importer.nexus {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.importer.nexus to build.jenesis.repository.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.nexus.NexusSourceProvider;
}
