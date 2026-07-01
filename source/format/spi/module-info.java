/**
 * The repository-format SPI: {@link build.jenesis.repository.format.RepositoryFormat} and the framework-neutral
 * {@link build.jenesis.repository.format.FormatExchange} it speaks through. A format (Maven, OCI, npm, PyPI,
 * NuGet) is a separate module that requires only this SPI and the storage SPI and {@code provides
 * RepositoryFormat}; the dispatcher discovers them with {@link java.util.ServiceLoader}, so layouts plug in
 * without the core, the dispatcher or the other formats knowing about them.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.format {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.format;
}
