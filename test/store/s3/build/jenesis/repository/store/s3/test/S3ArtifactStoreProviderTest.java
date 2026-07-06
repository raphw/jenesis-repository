package build.jenesis.repository.store.s3.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static build.jenesis.repository.store.s3.test.Requirement.requireOrSkip;

/**
 * Exercises {@link S3ArtifactStoreProvider} the way a deployment does - through {@link ArtifactStoreProvider#resolve}
 * over an injected config lookup - rather than the store class directly. It proves the provider's {@code create()}
 * wiring end to end against a real MinIO container: the {@code s3} backend is discovered by name via ServiceLoader,
 * the bucket is auto-created, the {@code JENESIS_AWS_ENDPOINT} switches on path-style access, and the static
 * {@code JENESIS_AWS_ACCESS_KEY_ID}/{@code JENESIS_AWS_SECRET_ACCESS_KEY} keys (the seam this task added) let the
 * whole wiring be driven from a test without touching the process environment - the filesystem provider's testability,
 * now for S3. The suite skips itself when no Docker daemon is reachable.
 */
@Tag("s3")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3ArtifactStoreProviderTest {

    private static final String IMAGE = "minio/minio";
    private static final int API_PORT = 9000;
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    private Docker minio;
    private int port;

    @BeforeAll
    public void start() throws Exception {
        requireOrSkip(Docker.available(), "Docker is required for the S3 (MinIO) provider integration test");
        minio = Docker.start(IMAGE, API_PORT, "server", "/data");
        port = minio.hostPort(API_PORT);
        awaitReady();
    }

    private void awaitReady() throws InterruptedException {
        // create() swallows a bucket-creation failure, so wait for MinIO to answer before driving the provider.
        try (S3Client probe = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .httpClient(UrlConnectionHttpClient.create())
                .forcePathStyle(true)
                .build()) {
            Instant deadline = Instant.now().plusSeconds(120);
            RuntimeException last = null;
            while (Instant.now().isBefore(deadline)) {
                try {
                    probe.listBuckets();
                    return;
                } catch (RuntimeException e) {
                    last = e;
                    Thread.sleep(1000);
                }
            }
            throw new IllegalStateException("MinIO did not become ready in time", last);
        }
    }

    @AfterAll
    public void stop() {
        if (minio != null) {
            minio.close();
        }
    }

    private UnaryOperator<String> config(String bucket) {
        Map<String, String> values = Map.of(
                "JENESIS_AWS_BUCKET", bucket,
                "JENESIS_AWS_ENDPOINT", "http://localhost:" + port,
                "JENESIS_AWS_ACCESS_KEY_ID", ACCESS_KEY,
                "JENESIS_AWS_SECRET_ACCESS_KEY", SECRET_KEY);
        return values::get;
    }

    @Test
    public void the_s3_backend_resolves_by_name_creates_its_bucket_and_round_trips() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("s3", config("provider-repo")).scope("acme");
        byte[] body = {4, 8, 15, 16, 23, (byte) 42};
        store.write("blobs/rt", new ByteArrayInputStream(body));
        assertThat(store.exists("blobs/rt")).isTrue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("blobs/rt", out);
        assertThat(out.toByteArray()).isEqualTo(body);
    }

    @Test
    public void a_missing_bucket_setting_is_a_clear_configuration_error() {
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve("s3", key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JENESIS_AWS_BUCKET");
    }
}
