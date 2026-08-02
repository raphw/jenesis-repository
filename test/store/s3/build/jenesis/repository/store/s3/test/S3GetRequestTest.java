package build.jenesis.repository.store.s3.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.s3.S3ArtifactStore;
import build.jenesis.repository.store.ArtifactStore;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@code s3} backend's GET-side wire contract without a network: a WireMock stub serves the sliver of the S3
 * XML API the store's reads touch - a seeded object served whole or over a requested {@code Range} window, a
 * {@code NoSuchKey} 404 for an absent key, and a non-404 HEAD fault - while the real SDK client drives it. That pins
 * two things the window-only assertion in {@code S3ArtifactStoreTest} cannot: that a ranged read really issues a
 * {@code Range: bytes=off-end} GET (so only the window crosses the wire, not the whole blob - asserted from the request
 * journal), and that {@link ArtifactStore#open} streams a stored blob back and surfaces a missing key as an
 * {@link IOException}. The objects are stubbed straight in (rather than through {@code write}, whose SDK PUT body is
 * aws-chunked / checksum-trailer framed) so the GET path is exercised over exact bytes. Needs no Docker, so it always
 * runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3GetRequestTest {

    private static final String NO_SUCH_KEY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error>"
            + "<Code>NoSuchKey</Code><Message>absent</Message></Error>";

    private WireMockServer server;
    private S3Client s3;
    private ArtifactStore store;

    @BeforeAll
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        server.start();
        // Defaults: an absent object is a NoSuchKey 404 on GET and a bodyless 404 on HEAD. Registered stubs (higher
        // precedence via the lower priority number) override for the seeded keys.
        server.stubFor(get(anyUrl()).atPriority(10)
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/xml").withBody(NO_SUCH_KEY)));
        server.stubFor(head(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(404)));
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + server.port()))
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
            server.stop();
        }
    }

    @Test
    public void a_ranged_read_issues_a_real_range_get_over_the_window() throws IOException {
        byte[] body = new byte[64];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        // Serve exactly the requested window for each Range the store is expected to push (a 200 with the sliced bytes,
        // mirroring the hand-rolled stub the SDK read happily).
        server.stubFor(get(urlPathEqualTo("/repo/acme/blobs/ranged")).atPriority(1)
                .withHeader("Range", equalTo("bytes=10-17"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"stub\"")
                        .withBody(Arrays.copyOfRange(body, 10, 18))));
        server.stubFor(get(urlPathEqualTo("/repo/acme/blobs/ranged")).atPriority(1)
                .withHeader("Range", equalTo("bytes=60-63"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"stub\"")
                        .withBody(Arrays.copyOfRange(body, 60, 64))));

        ByteArrayOutputStream window = new ByteArrayOutputStream();
        store.read("blobs/ranged", new RangeOutputStream(window, 10, 8));
        assertThat(lastRange()).as("the store pushed a bytes=off-end Range to the wire, not a whole-object GET")
                .isEqualTo("bytes=10-17");
        assertThat(window.toByteArray()).isEqualTo(Arrays.copyOfRange(body, 10, 18));

        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        store.read("blobs/ranged", new RangeOutputStream(tail, 60, 4));
        assertThat(lastRange()).as("the tail window is a real range to the last byte").isEqualTo("bytes=60-63");
        assertThat(tail.toByteArray()).isEqualTo(Arrays.copyOfRange(body, 60, 64));
    }

    @Test
    public void open_streams_a_stored_blob_back_and_a_missing_key_throws() throws IOException {
        byte[] body = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
        server.stubFor(get(urlPathEqualTo("/repo/acme/blobs/opened")).atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"stub\"").withBody(body)));
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
        // exists() dedup probe could skip re-uploading it during the outage). The stub answers a HEAD on the faulted
        // key with a 403, so exists() must throw (never return false) and size() must throw IOException (never -1).
        server.stubFor(head(urlPathEqualTo("/repo/acme/blobs/faulted")).atPriority(1)
                .willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> store.exists("blobs/faulted"))
                .as("a non-404 HEAD makes exists() fail loud, never report the object absent")
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.size("blobs/faulted"))
                .as("a non-404 HEAD makes size() throw IOException, never return -1")
                .isInstanceOf(IOException.class);
    }

    /** The {@code Range} header of the most recent request that carried one (events are newest-first). */
    private String lastRange() {
        return server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getHeader("Range"))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
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
