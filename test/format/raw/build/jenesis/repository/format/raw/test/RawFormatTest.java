package build.jenesis.repository.format.raw.test;

import build.jenesis.repository.format.raw.RawFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import module org.junit.jupiter.api;

import module java.base;

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
    void a_listed_leaf_that_a_get_would_not_serve_is_screened_out_of_the_listing() throws IOException {
        // The directory listing must disclose only what a GET would serve. A pointer can outlive its blob - a
        // withheld/retracted artifact, or one whose blob a garbage collection reclaimed - and located() (the same
        // screen GET and HEAD apply) then resolves it to empty and 404s. The old listing wrote every pointer name
        // verbatim, leaking the existence - and the name - of an artifact that no longer serves. The listing now
        // screens each leaf through located(), so the dangling pointer is dropped while a live sibling stays listed.
        byte[] body = "served bytes".getBytes(StandardCharsets.UTF_8);
        FakeExchange put = new FakeExchange("PUT", "/raw/mix/live.bin", body);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        // A pointer to a blob that is not present (reclaimed or withheld): GET 404s it, but list() still returns it.
        new Publication(store).link("/raw/mix/gone.bin",
                "0000000000000000000000000000000000000000000000000000000000000000");
        FakeExchange gone = new FakeExchange("GET", "/raw/mix/gone.bin");
        format.handle(gone, store);
        assertThat(gone.status()).as("a GET screens the dangling pointer out").isEqualTo(404);

        FakeExchange listing = new FakeExchange("GET", "/raw/mix/");
        format.handle(listing, store);
        assertThat(listing.status()).isEqualTo(200);
        assertThat(listing.responseText())
                .as("the live leaf is listed, the screened-out one is not disclosed")
                .contains("live.bin").doesNotContain("gone.bin");
    }

    @Test
    void a_directory_whose_only_leaf_is_screened_out_reports_absence() throws IOException {
        // A directory that holds nothing a GET would serve is indistinguishable from an empty one: it 404s rather
        // than rendering an empty document that still confirms the directory (and its screened child) exist.
        new Publication(store).link("/raw/hidden/gone.bin",
                "0000000000000000000000000000000000000000000000000000000000000000");
        FakeExchange listing = new FakeExchange("GET", "/raw/hidden/");
        format.handle(listing, store);
        assertThat(listing.status()).isEqualTo(404);
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
