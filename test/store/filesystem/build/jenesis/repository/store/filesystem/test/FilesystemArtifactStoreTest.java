package build.jenesis.repository.store.filesystem.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The default filesystem store exercised against the full {@link ArtifactStore} contract on a {@code @TempDir}: keyed
 * writes round-trip and stream, content-addressed writes dedupe and return the SHA-256, sizing and existence report
 * correctly, deletion tidies the empty containers it leaves behind, listing returns sorted immediate children while
 * hiding an atomic write's in-flight {@code .upload*.tmp}, tenant scoping confines a view to a subdirectory, the
 * last-modified compare-and-set of {@code writeVersioned} enforces create-if-absent and update-if-unchanged, and a key
 * that escapes the store root is rejected.
 */
class FilesystemArtifactStoreTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String key) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read(key, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_keyed_write_round_trips_through_read_and_open() throws IOException {
        store.write("a/b/c.bin", bytes("hello"));
        assertThat(store.exists("a/b/c.bin")).isTrue();
        assertThat(read("a/b/c.bin")).isEqualTo("hello");
        try (InputStream in = store.open("a/b/c.bin")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void write_blob_content_addresses_and_dedupes_and_reports_size() throws Exception {
        String hash = store.writeBlob(bytes("payload"));
        assertThat(hash).isEqualTo(sha256Hex("payload"));
        assertThat(store.exists("blobs/" + hash)).isTrue();
        assertThat(store.size("blobs/" + hash)).isEqualTo("payload".getBytes(StandardCharsets.UTF_8).length);

        String again = store.writeBlob(bytes("payload"));
        assertThat(again).as("identical content addresses to one blob").isEqualTo(hash);
    }

    @Test
    void size_is_minus_one_for_an_absent_key() throws IOException {
        assertThat(store.size("nope")).isEqualTo(-1L);
    }

    @Test
    void delete_removes_the_blob_and_tidies_the_empty_containers_it_leaves() throws IOException {
        store.write("a/b/c.bin", bytes("x"));
        store.delete("a/b/c.bin");
        assertThat(store.exists("a/b/c.bin")).isFalse();
        assertThat(store.list("a")).as("the now-empty parent directories are gone").isEmpty();
    }

    @Test
    void list_returns_sorted_immediate_children_and_hides_an_atomic_write_temp_file() throws IOException {
        store.write("d/one", bytes("1"));
        store.write("d/two", bytes("2"));
        Files.createFile(root.resolve("d").resolve(".upload12345.tmp"));

        assertThat(store.list("d")).containsExactly("one", "two");
        assertThat(store.list("missing")).isEmpty();
    }

    @Test
    void a_ranged_read_seeks_and_streams_only_the_window() throws IOException {
        store.write("r/blob", bytes("0123456789"));
        ByteArrayOutputStream window = new ByteArrayOutputStream();
        class Sink extends java.io.OutputStream implements ArtifactStore.RangedSink {
            @Override
            public long offset() {
                return 2;
            }

            @Override
            public long length() {
                return 3;
            }

            @Override
            public java.io.OutputStream sink() {
                return window;
            }

            @Override
            public void write(int b) {
                window.write(b);
            }
        }
        store.read("r/blob", new Sink());
        assertThat(window.toString(StandardCharsets.UTF_8)).isEqualTo("234");
    }

    @Test
    void write_versioned_tokens_strictly_advance_so_a_stale_token_never_passes() throws IOException {
        assertThat(store.writeVersioned("m/x", "0".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object token = store.readVersioned("m/x").orElseThrow().token();
        for (int update = 1; update <= 100; update++) {
            assertThat(store.writeVersioned("m/x", Integer.toString(update).getBytes(StandardCharsets.UTF_8), token))
                    .isTrue();
            Object next = store.readVersioned("m/x").orElseThrow().token();
            assertThat((long) next)
                    .as("the token advances on every update, even for updates inside one clock tick")
                    .isGreaterThan((long) token);
            token = next;
        }
    }

    @Test
    void concurrent_compare_and_set_updates_never_lose_one_another() throws Exception {
        assertThat(store.writeVersioned("m/counter", "0".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        int writers = 4, increments = 25;
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(writers)) {
            for (int writer = 0; writer < writers; writer++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < increments; i++) {
                        while (true) {
                            ArtifactStore.Versioned versioned = store.readVersioned("m/counter").orElseThrow();
                            int current = Integer.parseInt(new String(versioned.content(), StandardCharsets.UTF_8));
                            if (store.writeVersioned("m/counter",
                                    Integer.toString(current + 1).getBytes(StandardCharsets.UTF_8),
                                    versioned.token())) {
                                break;
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(new String(store.readVersioned("m/counter").orElseThrow().content(), StandardCharsets.UTF_8))
                .as("every compare-and-set increment landed; none was silently lost")
                .isEqualTo(Integer.toString(writers * increments));
    }

    @Test
    void an_aborted_write_leaves_no_partial_key_and_no_temp_file() throws IOException {
        InputStream aborting = new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (served++ < 3) {
                    return 'x';
                }
                throw new IOException("client hung up");
            }
        };

        assertThatThrownBy(() -> store.write("d/aborted", aborting)).isInstanceOf(IOException.class);

        assertThat(store.exists("d/aborted")).as("nothing lands at the key").isFalse();
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile))
                    .as("the atomic write's spool file is cleaned up, not leaked").isEmpty();
        }
    }

    @Test
    void a_scoped_view_confines_writes_to_the_tenant_subdirectory() throws IOException {
        ArtifactStore tenant = store.scope("acme");
        tenant.write("blobs/x", bytes("scoped"));

        assertThat(tenant.exists("blobs/x")).isTrue();
        assertThat(store.exists("acme/blobs/x")).as("the scope is a subdirectory of the root").isTrue();
        assertThat(store.exists("blobs/x")).isFalse();
    }

    @Test
    void a_scope_name_that_escapes_its_subspace_is_rejected_but_a_hidden_space_is_allowed() throws IOException {
        assertThatThrownBy(() -> store.scope("../escape"))
                .as("a parent traversal never scopes the store").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.scope("a/b"))
                .as("a path separator never scopes the store").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.scope(".."))
                .as("a bare parent segment is rejected").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.scope(""))
                .as("an empty segment is rejected").isInstanceOf(IllegalArgumentException.class);

        ArtifactStore hidden = store.scope(".tests");
        hidden.write("blobs/x", bytes("internal"));
        assertThat(store.exists(".tests/blobs/x"))
                .as("a hidden internal space (.tests / .scans) still scopes as a subdirectory").isTrue();
    }

    @Test
    void write_versioned_is_a_compare_and_set_on_the_last_modified_token() throws IOException {
        assertThat(store.writeVersioned("meta/m", "v1".getBytes(StandardCharsets.UTF_8), null))
                .as("create-if-absent succeeds against a null expectation").isTrue();

        Optional<ArtifactStore.Versioned> read = store.readVersioned("meta/m");
        assertThat(read).isPresent();
        assertThat(new String(read.get().content(), StandardCharsets.UTF_8)).isEqualTo("v1");
        Object token = read.get().token();
        assertThat(token).isNotNull();

        assertThat(store.writeVersioned("meta/m", "stale".getBytes(StandardCharsets.UTF_8), ((Long) token) + 1))
                .as("a stale token is rejected").isFalse();
        assertThat(store.writeVersioned("meta/m", "again".getBytes(StandardCharsets.UTF_8), null))
                .as("create-if-absent is rejected when the object already exists").isFalse();
        assertThat(new String(store.readVersioned("meta/m").orElseThrow().content(), StandardCharsets.UTF_8))
                .as("a rejected write leaves the stored content untouched").isEqualTo("v1");

        assertThat(store.writeVersioned("meta/m", "v2".getBytes(StandardCharsets.UTF_8), token))
                .as("update-if-unchanged succeeds against the current token").isTrue();
        assertThat(new String(store.readVersioned("meta/m").orElseThrow().content(), StandardCharsets.UTF_8))
                .isEqualTo("v2");
    }

    @Test
    void read_versioned_is_empty_for_an_absent_object() throws IOException {
        assertThat(store.readVersioned("meta/none")).isEmpty();
    }

    @Test
    void a_key_that_escapes_the_store_root_is_rejected() {
        assertThatThrownBy(() -> store.exists("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
