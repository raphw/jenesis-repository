/**
 * The import-source SPI - the read half of a migration. An {@link build.jenesis.repository.source.ImportSource}
 * enumerates a foreign repository's assets; an {@link build.jenesis.repository.source.ImportSourceProvider} builds one
 * for a named incumbent from an {@link build.jenesis.repository.source.ImportRequest}. A connector ships as its own
 * module that {@code provides} a provider, discovered with {@code ServiceLoader}, so the server supports another
 * incumbent by adding a module without knowing it. The import path's JSON ({@code Json}) is Jackson under the hood but
 * exposes only {@code Map}/{@code List}/scalar trees, so Jackson stays contained in this module and never crosses to a
 * connector or the server.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.21 SHA-256/53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b
 * @jenesis.pin tools.jackson.core/jackson-core 3.1.4 SHA-256/3bda1cd6eff0a8d47bdfcaeae7c2bd5311d6c8ed494ef5f3e51029bb44aa9bdf
 * @jenesis.pin tools.jackson.core/jackson-databind 3.1.4 SHA-256/14034bfdf392b6ebec1b4bb6c1de29d604f0aa97251259a19d5f19af8719bb20
 */
module build.jenesis.repository.source {
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.source;
}
