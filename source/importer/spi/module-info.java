/**
 * The import-source SPI - the read half of a migration. An {@link build.jenesis.repository.importer.ImportSource}
 * enumerates a foreign repository's assets; an {@link build.jenesis.repository.importer.ImportSourceProvider} builds one
 * for a named incumbent from an {@link build.jenesis.repository.importer.ImportRequest}. A connector ships as its own
 * module that {@code provides} a provider, discovered with {@code ServiceLoader}, so the server supports another
 * incumbent by adding a module without knowing it. Depends only on the format SPI (for the shared
 * {@code ProxyFormat.Fetcher}) and java.base; a connector reads and writes its own JSON with Jackson.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.importer {
    requires transitive build.jenesis.repository.format;
    exports build.jenesis.repository.importer;
}
