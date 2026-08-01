package build.jenesis.repository.store.s3.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.s3.S3ArtifactStore;
import build.jenesis.repository.store.ArtifactStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code presign} direct-fetch seam of {@link S3ArtifactStore}, both branches. SigV4 presigning is a purely local
 * signing operation - it contacts no server - so this needs no MinIO container and always runs: it builds the same
 * {@link S3Presigner} the provider wires (path-style, static keys, a fixed endpoint) and asserts a scoped
 * {@code presign(key, ttl)} mints a signed GET whose path carries the tenant scope prefix down to the signed object,
 * while a store built without a presigner (the two-arg constructor) degrades to {@link Optional#empty} so the caller
 * streams as today.
 */
class S3PresignTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final URI ENDPOINT = URI.create("https://s3.example.com");

    private static S3Client client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(ENDPOINT)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private static S3Presigner presigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(ENDPOINT)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
                .build();
    }

    @Test
    void presign_mints_a_signed_get_over_the_fully_qualified_scoped_key() {
        try (S3Client s3 = client(); S3Presigner presigner = presigner()) {
            ArtifactStore store = new S3ArtifactStore(s3, presigner, "repo").scope("acme");
            URI url = store.presign("blobs/x", TTL).orElseThrow();
            assertThat(url.getPath())
                    .as("path-style URL carries the bucket then the scope-prefixed key")
                    .isEqualTo("/repo/acme/blobs/x");
            assertThat(url.getQuery())
                    .as("a real SigV4 presigned URL carries the signature and its bounded expiry")
                    .contains("X-Amz-Signature=").contains("X-Amz-Expires=300");
            assertThat(url.getHost()).isEqualTo("s3.example.com");
        }
    }

    @Test
    void a_store_without_a_presigner_degrades_to_empty() {
        try (S3Client s3 = client()) {
            ArtifactStore store = new S3ArtifactStore(s3, "repo").scope("acme");
            assertThat(store.presign("blobs/x", TTL))
                    .as("no presigner configured -> stream as today, never a signed URL").isEmpty();
        }
    }
}
