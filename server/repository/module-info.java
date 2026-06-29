/**
 * The dual-layout repository server: it serves the same artifacts under the Maven layout ({@code /maven/...})
 * and the Jenesis module layout ({@code /module/...}, {@code /artifact/...}), generating the POM and
 * {@code maven-metadata.xml} when a module is uploaded. Headless and stateless over the ArtifactStore SPI
 * (filesystem by default; a cloud backend is selected through ArtifactStoreProvider.resolve via
 * {@code JENESIS_STORE}). This skeleton runs on the JDK HTTP server; a production build can swap in a
 * Spring Boot virtual-thread stack without touching the layout, bridge or POM code.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.repository.RepositoryServer
 */
module build.jenesis.repository {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    requires jdk.httpserver;
    requires java.net.http;
    exports build.jenesis.repository;
    uses build.jenesis.repository.format.RepositoryFormat;
    uses build.jenesis.repository.format.RepositoryImporter;
}
