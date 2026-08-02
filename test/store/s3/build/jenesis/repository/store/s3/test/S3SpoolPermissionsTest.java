package build.jenesis.repository.store.s3.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.s3.S3ArtifactStore;
import build.jenesis.repository.store.ArtifactStore;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upload-spool temp file {@link S3ArtifactStore#write} buffers a PUT body through (via {@code spool()}) is created
 * owner-only {@code rw-------} (POSIX 0600), so a regression cannot leave the plaintext artifact bytes world-readable in
 * the shared temp directory for the life of the upload. The subtle failure this guards: {@code spool()} creates the file
 * 0600, but a {@code Files.copy(in, temporary, REPLACE_EXISTING)} would DELETE and recreate it {@code CREATE_NEW} under
 * the process umask (typically 0644), silently undoing the owner-only attribute - so {@code write} must instead write
 * through the existing spool with a {@code TRUNCATE_EXISTING} open, which preserves the permission.
 *
 * <p>The spool exists only between its creation and the {@code finally} that deletes it, so a WireMock transformer parks
 * the object PUT on a barrier: it signals the moment the PUT is in flight (the spool is now on disk) and blocks until the
 * test has inspected the file's permissions, then releases the upload. Skips cleanly where the filesystem cannot express
 * POSIX permissions. No Docker, always runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3SpoolPermissionsTest {

    private WireMockServer server;
    private S3Client s3;
    private ArtifactStore store;
    private final Barrier barrier = new Barrier("/repo/acme/spool/aa01");

    @BeforeAll
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort()
                .extensions(barrier));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("ETag", "\"stub\"")));
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
    public void the_upload_spool_file_is_created_owner_only_0600() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "owner-only permission tightening is only observable on a POSIX filesystem");

        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = spoolFiles(tmp);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread upload = new Thread(() -> {
            try {
                store.write("spool/aa01", new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "s3-spool-upload");
        upload.start();
        try {
            assertThat(barrier.awaitInFlight(Duration.ofSeconds(15)))
                    .as("the PUT reached the stub, so the spool file is now on disk").isTrue();

            Path spool = newSpoolFile(tmp, before);
            assertThat(spool).as("write() created an s3-artifact- spool file while the upload was in flight").isNotNull();
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(spool)))
                    .as("the upload spool is rw-------, never a world-readable /tmp default").isEqualTo("rw-------");
        } finally {
            barrier.release();
            upload.join(Duration.ofSeconds(15).toMillis());
        }
        assertThat(failure.get()).as("the upload completed once released").isNull();
    }

    /** The {@code s3-artifact-} spool files currently in {@code dir} (best-effort; an unreadable dir yields empty). */
    private static Set<Path> spoolFiles(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().startsWith("s3-artifact-")).collect(Collectors.toSet());
        }
    }

    /** The first {@code s3-artifact-} spool file in {@code dir} not present in {@code before}, polling briefly for it. */
    private static Path newSpoolFile(Path dir, Set<Path> before) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            for (Path candidate : spoolFiles(dir)) {
                if (!before.contains(candidate)) {
                    return candidate;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    /**
     * A WireMock transformer that parks the request to one target path (the object PUT) until the test releases it,
     * signalling when the request is in flight. Every other request passes through with its matched response.
     */
    private static final class Barrier implements ResponseDefinitionTransformerV2 {

        private final String targetPath;
        private final CountDownLatch inFlight = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private Barrier(String targetPath) {
            this.targetPath = targetPath;
        }

        private boolean awaitInFlight(Duration timeout) throws InterruptedException {
            return inFlight.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void release() {
            released.countDown();
        }

        @Override
        public String getName() {
            return "s3-spool-barrier";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            String url = event.getRequest().getUrl();
            String path = url.indexOf('?') < 0 ? url : url.substring(0, url.indexOf('?'));
            if (RequestMethod.PUT.equals(event.getRequest().getMethod()) && path.equals(targetPath)) {
                inFlight.countDown();
                try {
                    released.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return aResponse().withStatus(200).withHeader("ETag", "\"stub\"").build();
            }
            return event.getResponseDefinition();
        }
    }
}
