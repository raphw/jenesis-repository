/**
 * The OCI / Docker registry format (the {@code /v2/} Distribution API) as a plugin module: {@code docker push}
 * and {@code docker pull} over the same content-addressed store. It requires the format SPI, the storage SPI and
 * Jackson (to read a manifest's media type and the token-auth response, and to write the tag list), no core - because
 * an OCI blob is addressed by its {@code sha256} digest, exactly the {@code blobs/<hex>} the store already uses.
 * Discovered through {@code provides}.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.21 SHA-256/53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b
 * @jenesis.pin tools.jackson.core/jackson-core 3.1.4 SHA-256/3bda1cd6eff0a8d47bdfcaeae7c2bd5311d6c8ed494ef5f3e51029bb44aa9bdf
 * @jenesis.pin tools.jackson.core/jackson-databind 3.1.4 SHA-256/14034bfdf392b6ebec1b4bb6c1de29d604f0aa97251259a19d5f19af8719bb20
 */
module build.jenesis.repository.format.oci {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires tools.jackson.databind;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.oci.OciFormat;
    provides build.jenesis.repository.format.RepositoryImporter
            with build.jenesis.repository.format.oci.OciImporter;
}
