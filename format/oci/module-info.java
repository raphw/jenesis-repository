/**
 * The OCI / Docker registry format (the {@code /v2/} Distribution API) as a plugin module: {@code docker push}
 * and {@code docker pull} over the same content-addressed store. Self-contained - it requires only the format SPI
 * and the storage SPI, no core - because an OCI blob is addressed by its {@code sha256} digest, exactly the
 * {@code blobs/<hex>} the store already uses. Discovered through {@code provides}.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.oci {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.oci.OciFormat;
    provides build.jenesis.repository.format.RepositoryImporter
            with build.jenesis.repository.oci.OciImporter;
}
