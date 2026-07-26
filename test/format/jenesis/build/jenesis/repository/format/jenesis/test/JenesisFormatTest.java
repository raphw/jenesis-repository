package build.jenesis.repository.format.jenesis.test;

import build.jenesis.repository.format.jenesis.JenesisFormat;
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
 * The Jenesis module layout driven through {@link JenesisFormat#handle}: it claims the {@code /module/} and
 * {@code /artifact/} prefixes, a PUT stores the blob content-addressed and links the path (201), a GET serves it back
 * byte for byte (200), and a GET of an unpublished path is a miss (404).
 */
class JenesisFormatTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final JenesisFormat format = new JenesisFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void name_and_handles_claim_the_module_and_artifact_prefixes() {
        assertThat(format.name()).isEqualTo("jenesis");
        assertThat(format.handles("/module/com.acme/1.0/com.acme.jar")).isTrue();
        assertThat(format.handles("/artifact/anything")).isTrue();
        assertThat(format.handles("/maven/org/example")).isFalse();
    }

    @Test
    void a_module_is_stored_and_served_and_a_miss_is_404() throws IOException {
        byte[] body = "modular jar bytes".getBytes(StandardCharsets.UTF_8);

        FakeExchange put = new FakeExchange("PUT", "/module/com.acme/1.0/com.acme.jar", body);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        FakeExchange get = new FakeExchange("GET", "/module/com.acme/1.0/com.acme.jar");
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(body);

        FakeExchange miss = new FakeExchange("GET", "/module/com.acme/9.9/com.acme.jar");
        format.handle(miss, store);
        assertThat(miss.status()).isEqualTo(404);
    }

    @Test
    void a_head_is_answered_from_the_stored_size_without_streaming_the_blob() throws IOException {
        byte[] body = "modular jar bytes".getBytes(StandardCharsets.UTF_8);
        format.handle(new FakeExchange("PUT", "/module/com.acme/1.0/com.acme.jar", body), store);

        FakeExchange head = new FakeExchange("HEAD", "/module/com.acme/1.0/com.acme.jar");
        format.handle(head, store);
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.responseBytes()).as("a HEAD answers from metadata, never streaming the blob body").isEmpty();
        assertThat(head.responseHeader("Content-Length"))
                .as("Content-Length is the stored blob size").isEqualTo(Long.toString(body.length));

        FakeExchange miss = new FakeExchange("HEAD", "/module/com.acme/9.9/com.acme.jar");
        format.handle(miss, store);
        assertThat(miss.status()).isEqualTo(404);
    }

    @Test
    void the_artifact_layout_round_trips_the_same_way() throws IOException {
        byte[] body = {4, 5, 6, 7};

        FakeExchange put = new FakeExchange("PUT", "/artifact/com.acme/1.0/notes.txt", body);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        FakeExchange get = new FakeExchange("GET", "/artifact/com.acme/1.0/notes.txt");
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(body);
    }
}
