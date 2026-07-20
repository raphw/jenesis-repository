package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven proxy's checksum-mismatch retraction, proven with <em>both</em> layout formats on the module path so the
 * one required cross-publish actually runs (the focused Maven unit test carries no module-view provider, so its
 * {@code MODULE_VIEWS} is empty). When a proxied modular jar fails its upstream SHA-1, it must be retracted from every
 * coordinate it was linked under: the {@code /maven/} pointer <em>and</em> the {@code /module/} views the accept path
 * cross-published - otherwise a tampered modular jar, retracted from its Maven coordinate, would still serve by module
 * name. The matching case is the control: it shows the cross-publish really happened, so the mismatch case retracts a
 * pointer that existed rather than passing vacuously. Answered from a fixed in-memory upstream, no network.
 */
class MavenProxyChecksumRetractionTest {

    private static final URI UPSTREAM = URI.create("https://upstream.example/maven2/");
    private static final String PATH = "/maven/org/example/widget/1.0/widget-1.0.jar";

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
    void a_matching_modular_jar_is_cross_published_to_its_module_views() throws IOException {
        byte[] jar = automaticModuleJar("test.verified");

        boolean served = format.proxy(new CaptureExchange(PATH), store, UPSTREAM, upstream(jar, 200, sha1(jar)));

        assertThat(served).isTrue();
        assertThat(publication.located(PATH)).as("cached under its Maven coordinate").isPresent();
        assertThat(publication.located("/module/test.verified/1.0/test.verified.jar"))
                .as("the cross-publish links the versioned module view").isPresent();
        assertThat(publication.located("/module/test.verified/test.verified.jar"))
                .as("the cross-publish links the latest module view").isPresent();
    }

    @Test
    void a_tampered_modular_jar_is_unreachable_by_its_maven_coordinate_and_by_module_name() throws IOException {
        byte[] jar = automaticModuleJar("test.tampered");

        boolean served = format.proxy(new CaptureExchange(PATH), store, UPSTREAM,
                upstream(jar, 200, "0000000000000000000000000000000000000000"));

        assertThat(served).as("a body that does not match its upstream checksum is refused").isFalse();
        assertThat(publication.located(PATH)).as("retracted from its Maven coordinate").isEmpty();
        assertThat(publication.located("/module/test.tampered/1.0/test.tampered.jar"))
                .as("retracted from its versioned module view too").isEmpty();
        assertThat(publication.located("/module/test.tampered/test.tampered.jar"))
                .as("retracted from its latest module view too").isEmpty();
    }

    /** An upstream that serves {@code artifact} for the jar and {@code sha1Hex} (at {@code sha1Status}) for its
     *  {@code .sha1} sibling. */
    private static ProxyFormat.Fetcher upstream(byte[] artifact, int sha1Status, String sha1Hex) {
        return (url, headers) -> url.toString().endsWith(".sha1")
                ? Optional.of(new ProxyFormat.Fetched(sha1Status,
                        sha1Hex.getBytes(StandardCharsets.UTF_8), Map.of()))
                : Optional.of(new ProxyFormat.Fetched(200, artifact, Map.of()));
    }

    private static byte[] automaticModuleJar(String moduleName) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.flush();
        }
        return bytes.toByteArray();
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A minimal {@link FormatExchange} that captures the served status and body - all the proxy needs to serve the
     *  matching-checksum control; the mismatch case returns before it responds. */
    private static final class CaptureExchange implements FormatExchange {

        private final String path;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        CaptureExchange(String path) {
            this.path = path;
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            return body;
        }
    }
}
