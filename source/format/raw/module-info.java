/**
 * The generic (raw) repository format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the {@code /raw/...} layout, a plain
 * content-addressed file store over the {@code Publication} primitives in the store module. Discovered through
 * {@code provides}.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.format.raw {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires java.xml;
    exports build.jenesis.repository.format.raw to build.jenesis.repository.format.raw.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.raw.RawFormat;
}
