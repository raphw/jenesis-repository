package build.jenesis.repository.format.raw.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.raw.RawFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raw format's pull-through proxy answered from a fixed in-memory upstream (no network): a local miss is fetched,
 * cached content-addressed and served in one call, a subsequent read is a local hit, an upstream miss lets the local
 * {@code 404} stand, and a directory listing is never proxied.
 */
class RawProxyTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final RawFormat format = new RawFormat();
    private final URI upstream = URI.create("https://upstream.example/root");

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static ProxyFormat.Fetcher serving(int status, byte[] body) {
        return (url, headers) -> Optional.of(new ProxyFormat.Fetched(status, body, Map.of()));
    }

    @Test
    void a_miss_is_proxied_cached_and_served_then_read_locally() throws IOException {
        byte[] body = "upstream file".getBytes(StandardCharsets.UTF_8);
        FakeExchange get = new FakeExchange("GET", "/raw/pkg/file.bin");

        boolean served = format.proxy(get, store, upstream, serving(200, body));
        assertThat(served).isTrue();
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(body);

        assertThat(new Publication(store).located("/raw/pkg/file.bin"))
                .as("the fetched artifact is cached for a later local hit").isPresent();
    }

    @Test
    void an_upstream_miss_lets_the_local_404_stand() throws IOException {
        FakeExchange get = new FakeExchange("GET", "/raw/pkg/absent.bin");
        boolean served = format.proxy(get, store, upstream, serving(404, new byte[0]));
        assertThat(served).isFalse();
    }

    @Test
    void a_directory_listing_is_not_proxied() throws IOException {
        FakeExchange listing = new FakeExchange("GET", "/raw/pkg/");
        boolean served = format.proxy(listing, store, upstream, serving(200, new byte[]{1}));
        assertThat(served).isFalse();
    }
}
