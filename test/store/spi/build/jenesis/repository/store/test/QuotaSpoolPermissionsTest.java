package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.QuotaArtifactStore;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blob-digest spool {@link QuotaArtifactStore#writeBlob} buffers a content-addressed upload through is created
 * owner-only {@code rw-------} (POSIX 0600). The quota decorator wraps EVERY backend, so its own spool must not leave
 * the plaintext artifact world-readable in the shared temp directory - which a default {@code createTempFile} (0644)
 * would, defeating the 0600 spool the s3/gcs delegates take one layer down.
 *
 * <p>The spool exists only for the life of {@code writeBlob}, so the upload runs on a thread fed a body that blocks
 * mid-read: it signals once the spool is on disk and being written (before any delegate call) and blocks until the
 * test has inspected the file's permissions, then releases. Skips cleanly where the filesystem cannot express POSIX
 * permissions. No Docker, always runs.
 */
class QuotaSpoolPermissionsTest {

    @TempDir
    Path root;

    @Test
    void the_blob_spool_file_is_created_owner_only_0600() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "owner-only permission tightening is only observable on a POSIX filesystem");

        ArtifactStore delegate = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        QuotaArtifactStore store = new QuotaArtifactStore(delegate, 1_000_000);

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

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread upload = new Thread(() -> {
            try {
                store.writeBlob(body);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "quota-spool-upload");
        upload.start();
        try {
            assertThat(inFlight.await(15, TimeUnit.SECONDS))
                    .as("the body was read into the spool, so the file is now on disk").isTrue();

            Path spool = newSpoolFile(tmp, before);
            assertThat(spool).as("writeBlob created a quota-blob- spool file while the upload was in flight").isNotNull();
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(spool)))
                    .as("the blob spool is rw-------, never a world-readable /tmp default").isEqualTo("rw-------");
        } finally {
            release.countDown();
            upload.join(Duration.ofSeconds(15).toMillis());
        }
        assertThat(failure.get()).as("the metered write completed once released").isNull();
    }

    private static Set<Path> spoolFiles(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().startsWith("quota-blob-")).collect(Collectors.toSet());
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
