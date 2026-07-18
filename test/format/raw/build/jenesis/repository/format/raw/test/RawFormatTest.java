package build.jenesis.repository.format.raw.test;

import build.jenesis.repository.format.raw.RawFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raw format driven directly through {@link RawFormat#handle}: a file is PUT (201), served back byte for byte with
 * an octet-stream content type (200), found by HEAD (200), listed under its directory as HTML (200), and removed by
 * DELETE (204), after which a GET, a HEAD and an empty listing all report absence (404).
 */
class RawFormatTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final RawFormat format = new RawFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void name_and_handles_claim_only_the_raw_prefix() {
        assertThat(format.name()).isEqualTo("raw");
        assertThat(format.handles("/raw/a/b")).isTrue();
        assertThat(format.handles("/maven/a")).isFalse();
    }

    @Test
    void a_file_is_stored_served_found_listed_and_deleted() throws IOException {
        byte[] body = "generic repository bytes".getBytes(StandardCharsets.UTF_8);

        FakeExchange put = new FakeExchange("PUT", "/raw/dir/file.bin", body);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        FakeExchange get = new FakeExchange("GET", "/raw/dir/file.bin");
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(body);
        assertThat(get.responseHeader("Content-Type")).isEqualTo("application/octet-stream");

        FakeExchange head = new FakeExchange("HEAD", "/raw/dir/file.bin");
        format.handle(head, store);
        assertThat(head.status()).isEqualTo(200);

        FakeExchange listing = new FakeExchange("GET", "/raw/dir/");
        format.handle(listing, store);
        assertThat(listing.status()).isEqualTo(200);
        assertThat(listing.responseHeader("Content-Type")).isEqualTo("text/html");
        assertThat(listing.responseText()).contains("file.bin");

        FakeExchange delete = new FakeExchange("DELETE", "/raw/dir/file.bin");
        format.handle(delete, store);
        assertThat(delete.status()).isEqualTo(204);

        FakeExchange gone = new FakeExchange("GET", "/raw/dir/file.bin");
        format.handle(gone, store);
        assertThat(gone.status()).isEqualTo(404);
    }

    @Test
    void a_quarantined_put_is_held_and_a_rejected_put_links_nothing() throws IOException {
        // Guards RawFormat.handle's PUT routing through Publication.publish and its disposition->status mapping:
        // QUARANTINE -> 202 and the path is withheld (GET 404, held under /quarantine); REJECT -> 422 and nothing is
        // linked. A revert to link(storeBlob(...)) would answer 201 and serve both.
        FakeExchange quarantined = new FakeExchange(
                "PUT", "/raw/gate-quarantine.bin", "held".getBytes(StandardCharsets.UTF_8));
        format.handle(quarantined, store);
        assertThat(quarantined.status()).isEqualTo(202);

        FakeExchange getHeld = new FakeExchange("GET", "/raw/gate-quarantine.bin");
        format.handle(getHeld, store);
        assertThat(getHeld.status()).as("a quarantined artifact is withheld from serving").isEqualTo(404);

        FakeExchange rejected = new FakeExchange(
                "PUT", "/raw/gate-reject.bin", "blocked".getBytes(StandardCharsets.UTF_8));
        format.handle(rejected, store);
        assertThat(rejected.status()).isEqualTo(422);

        FakeExchange getRejected = new FakeExchange("GET", "/raw/gate-reject.bin");
        format.handle(getRejected, store);
        assertThat(getRejected.status()).isEqualTo(404);
    }

    @Test
    void a_missing_file_and_an_empty_directory_report_absence() throws IOException {
        FakeExchange head = new FakeExchange("HEAD", "/raw/missing");
        format.handle(head, store);
        assertThat(head.status()).isEqualTo(404);

        FakeExchange listing = new FakeExchange("GET", "/raw/empty/");
        format.handle(listing, store);
        assertThat(listing.status()).isEqualTo(404);
    }
}
