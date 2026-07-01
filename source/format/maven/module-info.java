/**
 * The Maven layout as a plugin module ({@code /maven/...}): it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} and builds on the shared Java-layout module
 * ({@code JavaLayout}) and the store module's format-neutral {@code Publication}. When a modular jar is published, it
 * cross-publishes the jar's module view into the Jenesis layout over the bridge the shared module exports to just these
 * two: it {@code uses} the {@code ModuleView} the Jenesis format provides. This is the one required cross-publish, and
 * it goes one way - Maven into the module layout, never a module back to Maven. {@code MavenMetadata} is generated on
 * read here. Discovered through {@code provides}, so the layout plugs in like any other format.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format.maven {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format.java;
    requires java.xml;
    exports build.jenesis.repository.format.maven to build.jenesis.repository.format.maven.test;
    uses build.jenesis.repository.format.java.bridge.ModuleView;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.maven.MavenFormat;
    provides build.jenesis.repository.format.RepositoryImporter
            with build.jenesis.repository.format.maven.MavenImporter;
}
