/**
 * The Maven layout as a plugin module ({@code /maven/...}): it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} and builds on the shared Java-layout module
 * ({@code JavaLayout}, {@code PomGenerator}) and the store module's format-neutral {@code Publication}. It cross-publishes
 * with the Jenesis layout over the bridge the shared module exports to just these two: it {@code provides} the
 * {@code MavenView} (the Jenesis format's way to give a module its Maven view) and {@code uses} the {@code ModuleView}
 * (its own way to give a modular jar its module view). {@code MavenMetadata} is generated on read here. Discovered
 * through {@code provides}, so the layout plugs in like any other format.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format.maven {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format.java;
    exports build.jenesis.repository.format.maven to build.jenesis.repository.test;
    uses build.jenesis.repository.format.java.bridge.ModuleView;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.maven.MavenFormat;
    provides build.jenesis.repository.format.RepositoryImporter
            with build.jenesis.repository.format.maven.MavenImporter;
    provides build.jenesis.repository.format.java.bridge.MavenView
            with build.jenesis.repository.format.maven.MavenViewPublisher;
}
