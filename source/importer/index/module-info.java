/**
 * The format-native enumeration import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} answering to {@code index} that walks the
 * <em>format's own</em> published mirror-style index - the PEP 503 project list, an OCI registry's
 * {@code /v2/_catalog}, a {@code repodata}/{@code Packages} index - through the
 * {@link build.jenesis.repository.format.ProxyFormat#enumerate} seam, so migration-in is vendor-neutral for every
 * installed format that can enumerate, not just Maven. The requested ecosystem format is named up front; the
 * provider resolves it among the installed {@link build.jenesis.repository.format.RepositoryFormat}s and streams
 * each enumerated coordinate lazily to the orchestrator, which routes it to that format's own importer - so the
 * connector itself knows no ecosystem, and a new format's repositories become importable the moment its module
 * implements {@code enumerate}. Because jenesis emits these same standard indexes to serve native clients, a
 * jenesis repository is walkable by this connector too: migration off jenesis works over plain format protocols,
 * in both directions. Depends only on the import SPI and the format SPI.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.importer.index {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    exports build.jenesis.repository.importer.index to build.jenesis.repository.test, build.jenesis.repository.importer.index.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.index.IndexSourceProvider;
}
