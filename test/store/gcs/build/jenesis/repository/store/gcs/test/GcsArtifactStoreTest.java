package build.jenesis.repository.store.gcs.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.store.gcs.test.Requirement.requireOrSkip;

/**
 * Exercises the {@code gcs} backend's streaming contract the way a deployment does - through
 * {@link ArtifactStoreProvider#resolve} over an injected config lookup - against a MinIO container.
 * The backend's data path is GCS's S3-compatible XML surface, so the same emulator the {@code s3}
 * suite boots drives every generic operation: the {@code gcs} provider is discovered by name via
 * ServiceLoader, {@code JENESIS_GCS_ENDPOINT} redirects it from the {@code storage.googleapis.com}
 * default, the bucket is auto-created, and the static {@code JENESIS_GCS_ACCESS_KEY_ID} /
 * {@code JENESIS_GCS_SECRET_ACCESS_KEY} HMAC pair rides the config lookup without touching the
 * process environment. The GCS-specific conditional writes are NOT covered here - MinIO does not
 * honour {@code x-goog-if-generation-match} - they live in {@link GcsConditionalWriteTest} (an
 * in-process generation-aware stub) and in the {@link GcsLiveTest} smoke against real GCS. The suite
 * skips itself when no Docker daemon is reachable.
 */
@Tag("gcs")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GcsArtifactStoreTest {

    private static final String IMAGE = "minio/minio";
    private static final int API_PORT = 9000;
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    private Docker minio;
    private ArtifactStore root;
    private ArtifactStore store;

    @BeforeAll
    public void start() throws Exception {
        requireOrSkip(Docker.available(), "Docker is required for the GCS (MinIO) integration test");
        minio = Docker.start(IMAGE, API_PORT, "server", "/data");
        int port = minio.hostPort(API_PORT);
        Map<String, String> values = Map.of(
                "JENESIS_GCS_BUCKET", "repo",
                "JENESIS_GCS_ENDPOINT", "http://localhost:" + port,
                "JENESIS_GCS_REGION", "us-east-1",
                "JENESIS_GCS_ACCESS_KEY_ID", ACCESS_KEY,
                "JENESIS_GCS_SECRET_ACCESS_KEY", SECRET_KEY);
        root = awaitStore(values);
        store = root.scope("acme");
    }

    /**
     * Resolves the provider once MinIO answers: {@code create()} swallows a bucket-creation failure,
     * so retry the resolve-plus-probe until the container is ready rather than trusting the first
     * successful connection.
     */
    private static ArtifactStore awaitStore(Map<String, String> values) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(120);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                ArtifactStore resolved = ArtifactStoreProvider.resolve("gcs", values::get);
                resolved.list("");
                return resolved;
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("MinIO did not become ready in time", last);
    }

    @AfterAll
    public void stop() {
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
    public void scopes_are_isolated() throws IOException {
        ArtifactStore other = root.scope("globex");
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

        // The serving layer wraps a client Range in this OutputStream+RangedSink; GcsArtifactStore recognizes the
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
        // ListObjectsV2 caps a page at 1000 keys; writing 1001 forces the paginator across a page boundary, so
        // this proves list() concatenates pages rather than truncating at the first (a silent, hard-to-notice loss).
        int count = 1001;
        for (int i = 0; i < count; i++) {
            store.write("paged/" + String.format("%04d", i), new ByteArrayInputStream(new byte[]{1}));
        }
        List<String> children = store.list("paged");
        assertThat(children).hasSize(count);
        assertThat(children).contains("0000", "0999", "1000");
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
