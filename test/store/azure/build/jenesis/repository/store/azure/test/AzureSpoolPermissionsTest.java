package build.jenesis.repository.store.azure.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.azure.AzureArtifactStore;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blob-digest spool {@link AzureArtifactStore#writeBlob} buffers a content-addressed upload through is created
 * owner-only {@code rw-------} (POSIX 0600), matching the s3/gcs backends - so the plaintext artifact is never
 * world-readable in the shared temp directory for the upload's life (a default {@code createTempFile} would leave it
 * 0644). The spool is written and digested BEFORE any Azure call, so the body blocks mid-read to hold the file on disk
 * while the test inspects its permissions; the store's container client points at a closed port, so the never-reached
 * upload cannot hang the test. Skips cleanly where the filesystem cannot express POSIX permissions. No Docker.
 */
class AzureSpoolPermissionsTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Test
    void the_blob_spool_file_is_created_owner_only_0600() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "owner-only permission tightening is only observable on a POSIX filesystem");

        // A closed port: the spool is caught before any Azure call, and the never-reached upload fails fast, not hangs.
        String connectionString = "DefaultEndpointsProtocol=http;AccountName=" + ACCOUNT + ";AccountKey=" + KEY
                + ";BlobEndpoint=http://localhost:1/" + ACCOUNT + ";";
        BlobContainerClient container = new BlobServiceClientBuilder()
                .connectionString(connectionString).buildClient().getBlobContainerClient("repo");
        ArtifactStore store = new AzureArtifactStore(container).scope("acme");

        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = spoolFiles(tmp);

        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InputStream body = new InputStream() {
            private int emitted;

            @Override
            public int read() throws IOException {
                if (emitted < 4) {
                    emitted++;
                    return 1;
                }
                inFlight.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };

        Thread upload = new Thread(() -> {
            try {
                store.writeBlob(body);
            } catch (Throwable ignored) {
                // The upload past the spool hits the closed port and fails - irrelevant; the spool perms are the point.
            }
        }, "azure-spool-upload");
        upload.start();
        try {
            assertThat(inFlight.await(15, TimeUnit.SECONDS))
                    .as("the body was read into the spool, so the file is now on disk").isTrue();

            Path spool = newSpoolFile(tmp, before);
            assertThat(spool).as("writeBlob created an azure-artifact- spool file while the upload was in flight")
                    .isNotNull();
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(spool)))
                    .as("the blob spool is rw-------, never a world-readable /tmp default").isEqualTo("rw-------");
        } finally {
            release.countDown();
            upload.join(Duration.ofSeconds(15).toMillis());
        }
    }

    private static Set<Path> spoolFiles(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().startsWith("azure-artifact-"))
                    .collect(Collectors.toSet());
        }
    }

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
}
