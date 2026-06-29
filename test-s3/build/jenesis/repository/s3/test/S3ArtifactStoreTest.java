package build.jenesis.repository.s3.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.s3.S3ArtifactStore;
import build.jenesis.repository.store.ArtifactStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link S3ArtifactStore} against a real S3 API served by MinIO (a free, S3-compatible object
 * store the provider already documents as a supported endpoint). The provider's {@code create()} reads
 * process environment variables a test cannot set, so the test builds the SDK client itself (pointed at
 * the {@link Docker}-started container) and constructs {@link S3ArtifactStore} directly. Beyond the blob
 * and enumeration contract, the two {@code writeVersioned} tests prove the conditional compare-and-set
 * that makes the backend safe across nodes: a create only succeeds while the key is absent, and an update
 * only succeeds while the stored object is unchanged, each rejection coming straight from S3's
 * {@code If-None-Match}/{@code If-Match} precondition rather than a lock.
 */
@Tag("s3")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3ArtifactStoreTest {

    private static final String IMAGE = "minio/minio";
    private static final int API_PORT = 9000;
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    private Docker minio;
    private S3Client s3;
    private ArtifactStore store;

    @BeforeAll
    public void start() throws Exception {
        assumeTrue(Docker.available(), "Docker is required for the S3 (MinIO) integration test");
        minio = Docker.start(IMAGE, API_PORT, "server", "/data");
        int port = minio.hostPort(API_PORT);
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .httpClient(UrlConnectionHttpClient.create())
                .forcePathStyle(true)
                .build();
        awaitReady();
        s3.createBucket(b -> b.bucket("repo"));
        store = new S3ArtifactStore(s3, "repo").scope("acme");
    }

    private void awaitReady() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(120);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                s3.listBuckets();
                return;
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("MinIO did not become ready in time", last);
    }

    @AfterAll
    public void stop() {
        if (s3 != null) {
            s3.close();
        }
        if (minio != null) {
            minio.close();
        }
    }

    @Test
    public void a_blob_round_trips_and_exists_only_after_a_write() throws IOException {
        byte[] body = {0, 1, 2, 3, (byte) 0xFF};
        assertThat(store.exists("blobs/abc")).isFalse();
        store.write("blobs/abc", new ByteArrayInputStream(body));
        assertThat(store.exists("blobs/abc")).isTrue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("blobs/abc", out);
        assertThat(out.toByteArray()).isEqualTo(body);
        store.delete("blobs/abc");
        assertThat(store.exists("blobs/abc")).isFalse();
    }

    @Test
    public void list_returns_the_immediate_children_under_a_prefix() throws IOException {
        store.write("publish/maven/g/a/1.0/a-1.0.jar", new ByteArrayInputStream(new byte[]{1}));
        store.write("publish/maven/g/a/1.0/a-1.0.pom", new ByteArrayInputStream(new byte[]{2}));
        store.write("publish/maven/g/a/2.0/a-2.0.jar", new ByteArrayInputStream(new byte[]{3}));
        assertThat(store.list("publish/maven/g/a")).containsExactly("1.0", "2.0");
        assertThat(store.list("publish/maven/g/a/1.0")).containsExactly("a-1.0.jar", "a-1.0.pom");
        assertThat(store.list("publish/maven/g/a/1.0/a-1.0.jar")).isEmpty();
    }

    @Test
    public void write_versioned_is_a_create_if_absent_compare_and_set() throws IOException {
        String key = "config/create";
        assertThat(store.readVersioned(key)).isEmpty();
        assertThat(store.writeVersioned(key, "one".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThat(store.writeVersioned(key, "two".getBytes(StandardCharsets.UTF_8), null)).isFalse();
        ArtifactStore.Versioned stored = store.readVersioned(key).orElseThrow();
        assertThat(new String(stored.content(), StandardCharsets.UTF_8)).isEqualTo("one");
    }

    @Test
    public void write_versioned_is_an_update_if_unchanged_compare_and_set() throws IOException {
        String key = "config/update";
        assertThat(store.writeVersioned(key, "v1".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object token = store.readVersioned(key).orElseThrow().token();
        assertThat(store.writeVersioned(key, "v2".getBytes(StandardCharsets.UTF_8), token)).isTrue();
        assertThat(store.writeVersioned(key, "v3".getBytes(StandardCharsets.UTF_8), token)).isFalse();
        assertThat(new String(store.readVersioned(key).orElseThrow().content(), StandardCharsets.UTF_8)).isEqualTo("v2");
    }

    @Test
    public void scopes_are_isolated() throws IOException {
        ArtifactStore other = new S3ArtifactStore(s3, "repo").scope("globex");
        store.write("isolate/x", new ByteArrayInputStream(new byte[]{7}));
        assertThat(other.exists("isolate/x")).isFalse();
        assertThat(other.list("isolate")).isEmpty();
    }
}
