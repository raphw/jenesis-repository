/**
 * The Jenesis module layout as a plugin module ({@code /module/...}, {@code /artifact/...}): it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} and builds on the shared Java-layout module and the store
 * module's format-neutral {@code Publication}. It cross-publishes with the Maven layout over the bridge the shared module
 * exports to just these two: it {@code provides} the {@code ModuleView} (the Maven format's way to give a modular jar
 * its module view) and {@code uses} the {@code MavenView} (its own way to give a module a Maven view). Discovered
 * through {@code provides}, so the layout plugs in like any other format.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format.jenesis {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format.java;
    uses build.jenesis.repository.format.java.bridge.MavenView;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.jenesis.JenesisFormat;
    provides build.jenesis.repository.format.java.bridge.ModuleView
            with build.jenesis.repository.format.jenesis.ModuleViewPublisher;
}
