/**
 * The Maven / Jenesis-module repository format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} and builds on the shared Maven-layout primitives
 * ({@code Publication}, {@code MavenMetadata}) in the core. Discovered through {@code provides}, so the dual
 * layout is plugged in like any other format.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format.maven {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository;
    requires build.jenesis.repository.store;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.maven.MavenFormat;
    provides build.jenesis.repository.format.RepositoryImporter
            with build.jenesis.repository.format.maven.MavenImporter;
}
