/**
 * The vendor-neutral Maven import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} that walks <em>any</em> repository serving the Maven
 * layout over plain HTTP - Nexus, Artifactory, a plain httpd/nginx autoindex, a static bucket - without a vendor
 * API, so a migration source needs no per-incumbent adapter. Three enumeration strategies stack by availability: a
 * recursive directory-listing walk where the server exposes an autoindex, falling back to the published Nexus
 * repository index ({@code .index/nexus-maven-repository-index.gz}, read with a pure-JDK port of the jenesis-modules
 * crawler's chunk reader) for coordinates, each refreshed through its {@code maven-metadata.xml} for versions the
 * index lags behind. Honestly scoped twice over: the source must expose a listing or publish an index (one without
 * either - GitHub Packages, say - still needs its vendor API), and the walk is Maven-shaped - registry formats
 * (npm, pypi, ...) need their vendor API or format protocol. Depends only on the import SPI, the format SPI (for the shared fetcher) and
 * {@code java.xml} (for the metadata and pom documents); the server discovers it with {@code ServiceLoader}, so
 * generic-Maven support is present exactly when this module is on the path.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.importer.maven {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires java.xml;
    exports build.jenesis.repository.importer.maven to build.jenesis.repository.test, build.jenesis.repository.importer.maven.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.maven.MavenSourceProvider;
}
