/**
 * The OCI / Docker registry format (the {@code /v2/} Distribution API) as a plugin module: {@code docker push}
 * and {@code docker pull} over the same content-addressed store. It requires the format SPI, the storage SPI and
 * Jackson (to read a manifest's media type and the token-auth response, and to write the tag list), no core - because
 * an OCI blob is addressed by its {@code sha256} digest, exactly the {@code blobs/<hex>} the store already uses.
 * Discovered through {@code provides}.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.0 SHA-256/5e353ce53c6901105dfcbf183e3220c17072e334e552b818a4bb1b99decea596
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.0 SHA-256/3ef94a3dddeafc247c50230fad0315981b2ce4ae6e91cfb4368a86f328904e4f
 * @jenesis.pin tools.jackson.databind 3.2.0
 */
module build.jenesis.repository.format.oci {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.oci to build.jenesis.repository.format.oci.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.oci.OciFormat;
    provides build.jenesis.repository.format.RepositoryImporter
            with build.jenesis.repository.format.oci.OciImporter;
}
