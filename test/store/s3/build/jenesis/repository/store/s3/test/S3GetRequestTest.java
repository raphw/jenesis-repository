package build.jenesis.repository.store.s3.test;

import module java.base;
import module jdk.httpserver;
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

/**
 * Proves the {@code s3} backend's GET-side wire contract without a network: an in-process {@code jdk.httpserver} stub
 * serves the sliver of the S3 XML API the store's reads touch - it holds a seeded object, records the {@code Range}
 * request header, serves the requested window on a ranged GET and a {@code NoSuchKey} 404 for an absent key - while the
 * real SDK client drives it. That pins two things the window-only assertion in {@code S3ArtifactStoreTest} cannot: that
 * a ranged read really issues a {@code Range: bytes=off-end} GET (so only the window crosses the wire, not the whole
 * blob), and that {@link ArtifactStore#open} streams a stored blob back and surfaces a missing key as an
 * {@link IOException}. The objects are seeded straight into the stub (rather than through {@code write}, whose SDK PUT
 * body is aws-chunked / checksum-trailer framed) so the GET path is exercised over exact bytes. Needs no Docker, so it
 * always runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3GetRequestTest {

    private HttpServer server;
    private S3Client s3;
    private ArtifactStore store;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastRange = new AtomicReference<>();

    @BeforeAll
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + server.getAddress().getPort()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
                .httpClient(UrlConnectionHttpClient.create())
                .forcePathStyle(true)
                .build();
        store = new S3ArtifactStore(s3, "repo").scope("acme");
    }

    @AfterAll
    public void stop() {
        if (s3 != null) {
            s3.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String key = path.startsWith("/repo/") ? path.substring("/repo/".length()) : "";
            exchange.getRequestBody().readAllBytes();
            if ("HEAD".equals(exchange.getRequestMethod())) {
                // exists()/size() issue a HEAD (headObject). Only a 404 means absent; a non-404 - here a 403 auth
                // failure, as a 503 throttle would be - must fail the request loudly, never be reported as absent.
                exchange.sendResponseHeaders(key.endsWith("faulted") ? 403 : 404, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(501, -1);
                return;
            }
            byte[] data = objects.get(key);
            if (data == null) {
                byte[] error = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error>"
                        + "<Code>NoSuchKey</Code><Message>absent</Message></Error>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, error.length);
                exchange.getResponseBody().write(error);
                return;
            }
            byte[] out = data;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                lastRange.set(range);
                String[] bounds = range.substring("bytes=".length()).split("-");
                int from = Integer.parseInt(bounds[0]);
                int to = Integer.parseInt(bounds[1]);
                out = Arrays.copyOfRange(data, from, Math.min(to + 1, data.length));
            }
            exchange.getResponseHeaders().set("ETag", "\"stub\"");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
        }
    }

    @Test
    public void a_ranged_read_issues_a_real_range_get_over_the_window() throws IOException {
        byte[] body = new byte[64];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        objects.put("acme/blobs/ranged", body);

        ByteArrayOutputStream window = new ByteArrayOutputStream();
        store.read("blobs/ranged", new RangeOutputStream(window, 10, 8));
        assertThat(lastRange.get())
                .as("the store pushed a bytes=off-end Range to the wire, not a whole-object GET").isEqualTo("bytes=10-17");
        assertThat(window.toByteArray()).isEqualTo(Arrays.copyOfRange(body, 10, 18));

        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        store.read("blobs/ranged", new RangeOutputStream(tail, 60, 4));
        assertThat(lastRange.get()).as("the tail window is a real range to the last byte").isEqualTo("bytes=60-63");
        assertThat(tail.toByteArray()).isEqualTo(Arrays.copyOfRange(body, 60, 64));
    }

    @Test
    public void open_streams_a_stored_blob_back_and_a_missing_key_throws() throws IOException {
        byte[] body = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
        objects.put("acme/blobs/opened", body);
        try (InputStream in = store.open("blobs/opened")) {
            assertThat(in.readAllBytes()).as("open() streams the stored bytes back").isEqualTo(body);
        }
        assertThatThrownBy(() -> store.open("blobs/absent"))
                .as("open() on a missing key surfaces an IOException, never a silent empty stream")
                .isInstanceOf(IOException.class);
    }

    @Test
    public void exists_and_size_fail_loud_on_a_non_404_head() {
        // The existence screen must distinguish absent (404 -> false / -1) from a backend fault: a 403 auth failure
        // (like a 503 throttle) has to surface, or a live artifact reads as a silent 404 miss (and writeBlob's
        // exists() dedup probe could skip re-uploading it during the outage). The stub answers a HEAD on "*/faulted"
        // with a 403, so exists() must throw (never return false) and size() must throw IOException (never return -1).
        assertThatThrownBy(() -> store.exists("blobs/faulted"))
                .as("a non-404 HEAD makes exists() fail loud, never report the object absent")
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.size("blobs/faulted"))
                .as("a non-404 HEAD makes size() throw IOException, never return -1")
                .isInstanceOf(IOException.class);
    }

    /** Mirrors the server's range sink: forwards only a window of the bytes written, and is a {@link
     *  ArtifactStore.RangedSink} the store seeks to. */
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
