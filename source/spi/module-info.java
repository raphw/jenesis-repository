/**
 * The import-source SPI - the read half of a migration. An {@link build.jenesis.repository.source.ImportSource}
 * enumerates a foreign repository's assets; an {@link build.jenesis.repository.source.ImportSourceProvider} builds one
 * for a named incumbent from an {@link build.jenesis.repository.source.ImportRequest}. A connector ships as its own
 * module that {@code provides} a provider, discovered with {@code ServiceLoader}, so the server supports another
 * incumbent by adding a module without knowing it. Depends only on the format SPI (for the shared
 * {@code ProxyFormat.Fetcher}) and java.base; it carries a small hand-rolled {@code Json} reader so the import path
 * needs no JSON library.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.source {
    requires build.jenesis.repository.format;
    exports build.jenesis.repository.source;
}
