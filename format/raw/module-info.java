/**
 * The generic (raw) repository format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the {@code /raw/...} layout, a plain
 * content-addressed file store over the {@code Publication} primitives in the store module. Discovered through
 * {@code provides}.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format.raw {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.raw.RawFormat;
    provides build.jenesis.repository.format.RepositoryImporter
            with build.jenesis.repository.format.raw.RawImporter;
}
