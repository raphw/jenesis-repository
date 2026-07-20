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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static build.jenesis.repository.store.s3.test.Requirement.requireOrSkip;

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
    // The store server-side-encrypts every write (SSE-S3), and MinIO rejects an SSE request unless a KMS is
    // configured ("Server side encryption specified but KMS is not configured", HTTP 501). Enable MinIO's built-in
    // KMS with a fixed test key so the encrypted round-trips this suite drives are honored.
    private static final String KMS_SECRET_KEY = "minio-default-key:OSMM+vkKUTCvQs9YL/CVMIMt43HFhkUpqJxTmGl6rYw=";

    private Docker minio;
    private S3Client s3;
    private ArtifactStore store;

    @BeforeAll
    public void start() throws Exception {
        requireOrSkip(Docker.available(), "Docker is required for the S3 (MinIO) integration test");
        minio = Docker.start(IMAGE, API_PORT, Map.of("MINIO_KMS_SECRET_KEY", KMS_SECRET_KEY), "server", "/data");
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
        assertThat(store.size("blobs/abc")).as("absent is -1").isEqualTo(-1L);
        store.write("blobs/abc", new ByteArrayInputStream(body));
        assertThat(store.exists("blobs/abc")).isTrue();
        assertThat(store.size("blobs/abc")).as("the stored byte length").isEqualTo(body.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("blobs/abc", out);
        assertThat(out.toByteArray()).isEqualTo(body);
        store.delete("blobs/abc");
        assertThat(store.exists("blobs/abc")).isFalse();
    }

    @Test
    public void write_blob_is_content_addressed_streaming_and_dedupes() throws Exception {
        byte[] body = {0, 1, 2, 3, (byte) 0xFF};
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String returned = store.writeBlob(new ByteArrayInputStream(body));
        assertThat(returned).as("the returned hash is the content's SHA-256").isEqualTo(hash);
        assertThat(store.exists("blobs/" + hash)).isTrue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("blobs/" + hash, out);
        assertThat(out.toByteArray()).isEqualTo(body);
        assertThat(store.writeBlob(new ByteArrayInputStream(body)))
                .as("identical content dedupes to the same blob").isEqualTo(hash);
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
    public void write_versioned_surfaces_a_missing_bucket_as_a_transport_error() {
        // A bucket-level 404 (NoSuchBucket) is a misconfiguration or outage, not the benign key-level 404 that maps
        // to a false CAS conflict; a versioned write against a bucket that does not exist must surface a real
        // IOException rather than turn a broken deployment into silent retry-exhaustion at the caller.
        ArtifactStore missing = new S3ArtifactStore(s3, "no-such-bucket").scope("acme");
        assertThatThrownBy(() -> missing.writeVersioned("config/x", "v".getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    public void scopes_are_isolated() throws IOException {
        ArtifactStore other = new S3ArtifactStore(s3, "repo").scope("globex");
        store.write("isolate/x", new ByteArrayInputStream(new byte[]{7}));
        assertThat(other.exists("isolate/x")).isFalse();
        assertThat(other.list("isolate")).isEmpty();
    }

    @Test
    public void a_ranged_read_seeks_to_the_window_over_a_real_range_get() throws IOException {
        byte[] body = new byte[64];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        store.write("blobs/ranged", new ByteArrayInputStream(body));

        // The serving layer wraps a client Range in this OutputStream+RangedSink; S3ArtifactStore recognizes the
        // RangedSink and issues a `Range: bytes=10-17` GET, so only the window ever crosses the wire, and the sink
        // receives exactly those bytes. A store that ignored the RangedSink would still slice correctly here - this
        // asserts the window is right; the ranged GET is what avoids pulling the whole blob to serve a slice.
        ByteArrayOutputStream window = new ByteArrayOutputStream();
        store.read("blobs/ranged", new RangeOutputStream(window, 10, 8));
        assertThat(window.toByteArray()).isEqualTo(Arrays.copyOfRange(body, 10, 18));

        // A window that runs to the last byte reads to end-of-object without a 416.
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        store.read("blobs/ranged", new RangeOutputStream(tail, 60, 4));
        assertThat(tail.toByteArray()).isEqualTo(Arrays.copyOfRange(body, 60, 64));
    }

    @Test
    public void list_pages_past_the_thousand_key_boundary() throws IOException {
        // S3 ListObjectsV2 caps a page at 1000 keys; writing 1001 forces the paginator across a page boundary, so
        // this proves list() concatenates pages rather than truncating at the first (a silent, hard-to-notice loss).
        int count = 1001;
        for (int i = 0; i < count; i++) {
            store.writeVersioned("paged/" + String.format("%04d", i), new byte[]{1}, null);
        }
        List<String> children = store.list("paged");
        assertThat(children).hasSize(count);
        assertThat(children).contains("0000", "0999", "1000");

        // page() rides the same paginator natively: the full traversal crosses the page boundary too, and a
        // start-after resume is a server-side seek that returns exactly the names past the boundary, in order.
        List<String> paged = new ArrayList<>();
        store.page("paged", "", count, paged::add);
        assertThat(paged).hasSize(count);
        assertThat(paged).isSorted();
        List<String> resumed = new ArrayList<>();
        store.page("paged", "0499", 3, resumed::add);
        assertThat(resumed).containsExactly("0500", "0501", "0502");
    }

    @Test
    public void page_streams_ordered_children_strictly_after_the_boundary() throws IOException {
        store.writeVersioned("walkpage/apple", new byte[]{1}, null);
        store.writeVersioned("walkpage/banana/nested", new byte[]{1}, null);
        store.writeVersioned("walkpage/banana.txt", new byte[]{1}, null);
        store.writeVersioned("walkpage/cherry", new byte[]{1}, null);
        List<String> all = new ArrayList<>();
        store.page("walkpage", "", 10, all::add);
        assertThat(all).as("objects and grouped prefixes merge back into one lexicographic stream")
                .containsExactly("apple", "banana", "banana.txt", "cherry");
        List<String> after = new ArrayList<>();
        store.page("walkpage", "banana", 10, after::add);
        assertThat(after).as("the boundary name is excluded even where a same-named container leaves a grouped"
                + " prefix past the server-side start-after").containsExactly("banana.txt", "cherry");
        List<String> capped = new ArrayList<>();
        store.page("walkpage", "", 2, capped::add);
        assertThat(capped).containsExactly("apple", "banana");
    }

    /** Mirrors the server's range sink: forwards only a window of the bytes written, and is a {@link
     *  ArtifactStore.RangedSink} the store seeks to. Kept local so the test drives the real serving shape. */
    private static final class RangeOutputStream extends OutputStream implements ArtifactStore.RangedSink {

        private final OutputStream out;
        private final long start;
        private final long length;
        private long skip;
        private long remaining;

        private RangeOutputStream(OutputStream out, long start, long length) {
            this.out = out;
            this.start = start;
            this.length = length;
            this.skip = start;
            this.remaining = length;
        }

        @Override
        public long offset() {
            return start;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public OutputStream sink() {
            return out;
        }

        @Override
        public void write(int b) throws IOException {
            if (skip > 0) {
                skip--;
            } else if (remaining > 0) {
                out.write(b);
                remaining--;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (skip > 0) {
                long skipped = Math.min(skip, length);
                skip -= skipped;
                offset += (int) skipped;
                length -= (int) skipped;
            }
            if (remaining > 0 && length > 0) {
                int written = (int) Math.min(remaining, length);
                out.write(bytes, offset, written);
                remaining -= written;
            }
        }
    }
}
