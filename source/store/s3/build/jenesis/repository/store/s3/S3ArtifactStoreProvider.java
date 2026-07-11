package build.jenesis.repository.store.s3;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The {@code s3} artifact-store backend over any S3-compatible bucket (AWS S3, GCS via the XML API,
 * MinIO, LocalStack). Selected with {@code jenesis.repository.store=s3}; configured by
 * {@code JENESIS_AWS_BUCKET} (required), {@code JENESIS_AWS_REGION} (default {@code us-east-1}) and an
 * optional {@code JENESIS_AWS_ENDPOINT} (an S3-compatible endpoint, enabling path-style access).
 * Credentials come from the standard AWS chain (environment, profile or instance role) unless
 * {@code JENESIS_AWS_ACCESS_KEY_ID} and {@code JENESIS_AWS_SECRET_ACCESS_KEY} are both supplied through
 * the config lookup, in which case those static keys are used - the path a self-hosted S3-compatible
 * store (MinIO, Ceph) takes, and the seam that lets a test drive {@code create()} end to end against a
 * container through an injected config lookup, without touching the process environment. The blob I/O
 * and the conditional compare-and-set semantics live in {@link S3ArtifactStore}.
 */
public final class S3ArtifactStoreProvider implements ArtifactStoreProvider {

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public Set<String> requiredConfig() {
        // The credentials may come from the ambient AWS chain (environment, profile, instance role), so only the
        // bucket is required configuration.
        return Set.of("JENESIS_AWS_BUCKET");
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String bucket = config.apply("JENESIS_AWS_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("JENESIS_AWS_BUCKET is required for the s3 artifact store backend.");
        }
        String region = config.apply("JENESIS_AWS_REGION");
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(credentials(config));
        String endpoint = config.apply("JENESIS_AWS_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        S3Client s3 = builder.build();
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (S3Exception ignored) {
            // The bucket may already exist or the credentials may not permit creation; the operations
            // below surface a clear error if the bucket is truly unusable.
        }
        return new S3ArtifactStore(s3, bucket);
    }

    /**
     * Static keys when both {@code JENESIS_AWS_ACCESS_KEY_ID} and {@code JENESIS_AWS_SECRET_ACCESS_KEY} are
     * present in the config lookup, otherwise the standard AWS chain (environment, profile, instance role).
     */
    private static AwsCredentialsProvider credentials(UnaryOperator<String> config) {
        String accessKey = config.apply("JENESIS_AWS_ACCESS_KEY_ID");
        String secretKey = config.apply("JENESIS_AWS_SECRET_ACCESS_KEY");
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }
}
