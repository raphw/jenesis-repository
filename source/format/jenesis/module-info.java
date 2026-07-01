/**
 * The Jenesis module layout as a plugin module ({@code /module/...}, {@code /artifact/...}): it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} and serves over the store module's format-neutral
 * {@code Publication}. It {@code provides} the {@code ModuleView} the Maven format uses to give a published modular jar
 * its module view - the one-way cross-publish, Maven into the module layout; it does not mirror a module back to Maven.
 * Discovered through {@code provides}, so the layout plugs in like any other format.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format.jenesis {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format.java;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.jenesis.JenesisFormat;
    provides build.jenesis.repository.format.java.bridge.ModuleView
            with build.jenesis.repository.format.jenesis.ModuleViewPublisher;
}
