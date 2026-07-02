package build.jenesis.repository.format.maven.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven proxy verifies a fetched artifact against its upstream {@code .sha1} sibling before it is cached and
 * served: a matching checksum is cached and served, a mismatch is refused and left uncached, and an artifact the
 * upstream publishes no checksum for is served unverified rather than refused. Answered from a fixed in-memory
 * upstream, no network.
 */
class MavenProxyChecksumTest {

    private static final URI UPSTREAM = URI.create("https://upstream.example/maven2/");
    private static final String PATH = "/maven/org/example/lib/1.0/lib-1.0.jar";

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final MavenFormat format = new MavenFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void a_matching_upstream_checksum_is_cached_and_served() throws IOException {
        byte[] jar = "verified jar bytes".getBytes(StandardCharsets.UTF_8);
        FakeExchange get = new FakeExchange("GET", PATH);

        boolean served = format.proxy(get, store, UPSTREAM, upstream(jar, 200, sha1(jar)));

        assertThat(served).isTrue();
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(jar);
        assertThat(publication.located(PATH)).as("a verified artifact is cached for a later hit").isPresent();
    }

    @Test
    void a_checksum_mismatch_is_refused_and_left_uncached() throws IOException {
        byte[] jar = "tampered jar bytes".getBytes(StandardCharsets.UTF_8);
        FakeExchange get = new FakeExchange("GET", PATH);

        boolean served = format.proxy(get, store, UPSTREAM,
                upstream(jar, 200, "0000000000000000000000000000000000000000"));

        assertThat(served).as("a body that does not match its upstream checksum is refused").isFalse();
        assertThat(publication.located(PATH)).as("the corrupt artifact is not left cached").isEmpty();
    }

    @Test
    void an_artifact_without_an_upstream_checksum_is_served_unverified() throws IOException {
        byte[] jar = "unchecksummed jar".getBytes(StandardCharsets.UTF_8);
        FakeExchange get = new FakeExchange("GET", PATH);

        boolean served = format.proxy(get, store, UPSTREAM, upstream(jar, 404, null));

        assertThat(served).as("an artifact the upstream has no checksum for is still served").isTrue();
        assertThat(get.responseBytes()).isEqualTo(jar);
    }

    /** An upstream that serves {@code artifact} for the jar and {@code sha1Hex} (at {@code sha1Status}) for its
     *  {@code .sha1} sibling. */
    private static ProxyFormat.Fetcher upstream(byte[] artifact, int sha1Status, String sha1Hex) {
        return (url, headers) -> url.toString().endsWith(".sha1")
                ? Optional.of(new ProxyFormat.Fetched(sha1Status,
                        sha1Hex == null ? new byte[0] : sha1Hex.getBytes(StandardCharsets.UTF_8), Map.of()))
                : Optional.of(new ProxyFormat.Fetched(200, artifact, Map.of()));
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
