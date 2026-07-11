package build.jenesis.repository.store.gcs;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.function.UnaryOperator;

/**
 * The {@code gcs} artifact-store backend over a Google Cloud Storage bucket, through GCS's
 * S3-compatible XML API on the modular AWS SDK v2 - no Google SDK is added to the closure. Selected
 * with {@code jenesis.repository.store=gcs}; configured by {@code JENESIS_GCS_BUCKET} (required) and
 * an HMAC key pair {@code JENESIS_GCS_ACCESS_KEY_ID} / {@code JENESIS_GCS_SECRET_ACCESS_KEY} (a
 * secret; issued under Cloud Storage &gt; Settings &gt; Interoperability), with an optional
 * {@code JENESIS_GCS_ENDPOINT} (default {@code https://storage.googleapis.com}; point it elsewhere
 * for an emulator) and {@code JENESIS_GCS_REGION} (the SigV4 scope region, default {@code auto} as
 * GCS documents). When the key pair is absent the standard AWS chain is consulted, which keeps the
 * provider drivable end to end from a test through an injected config lookup. Two SDK defaults are
 * dialled back for GCS, which does not decode aws-chunked request bodies: the flexible-checksum
 * integrity protections become {@code WHEN_REQUIRED} (their trailing checksums ride aws-chunked
 * encoding) and chunked payload signing is disabled outright, so every upload is a plain body with a
 * whole-payload signature. The blob I/O and the generation-based conditional writes live in
 * {@link GcsArtifactStore}.
 */
public final class GcsArtifactStoreProvider implements ArtifactStoreProvider {

    @Override
    public String name() {
        return "gcs";
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String bucket = config.apply("JENESIS_GCS_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("JENESIS_GCS_BUCKET is required for the gcs artifact store backend.");
        }
        String region = config.apply("JENESIS_GCS_REGION");
        if (region == null || region.isBlank()) {
            region = "auto";
        }
        String endpoint = config.apply("JENESIS_GCS_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "https://storage.googleapis.com";
        }
        S3Client s3 = S3Client.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(credentials(config))
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .build();
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (S3Exception ignored) {
            // A GCS bucket is provisioned out of band (the XML API's create needs a project header the
            // S3 dialect cannot carry), and on any endpoint the bucket may already exist or the
            // credentials may not permit creation; the operations below surface a clear error if the
            // bucket is truly unusable.
        }
        return new GcsArtifactStore(s3, bucket);
    }

    /**
     * The static HMAC pair when both {@code JENESIS_GCS_ACCESS_KEY_ID} and
     * {@code JENESIS_GCS_SECRET_ACCESS_KEY} are present in the config lookup, otherwise the standard
     * AWS chain (environment, profile, instance role).
     */
    private static AwsCredentialsProvider credentials(UnaryOperator<String> config) {
        String accessKey = config.apply("JENESIS_GCS_ACCESS_KEY_ID");
        String secretKey = config.apply("JENESIS_GCS_SECRET_ACCESS_KEY");
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }
}
