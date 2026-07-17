package build.jenesis.repository.format.maven.test;

import build.jenesis.repository.format.maven.MavenMetadata;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The artifact-level {@code maven-metadata.xml} generated on read from the version folders published under a
 * coordinate: the version order follows a Maven-style comparison, {@code <release>} skips snapshots, and the
 * rendered bytes are a pure function of the version set so a checksum re-fetch is stable.
 */
class MavenMetadataTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private MavenMetadata metadata;

    private void publish(List<String> versions) throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        Publication publication = new Publication(store);
        for (String version : versions) {
            publication.link("/maven/org/example/lib/" + version + "/lib-" + version + ".jar", "abc" + version);
        }
        metadata = new MavenMetadata(store);
    }

    @Test
    void versions_are_listed_in_maven_order_with_latest_and_release() throws IOException {
        publish(List.of("1.10", "1.9", "1.0-alpha", "1.0", "2.0-SNAPSHOT"));
        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).contains("<groupId>org.example</groupId>").contains("<artifactId>lib</artifactId>");
        assertThat(xml).contains("<latest>2.0-SNAPSHOT</latest>");
        assertThat(xml).contains("<release>1.10</release>");
        assertThat(order(xml, "1.0-alpha", "1.0", "1.9", "1.10", "2.0-SNAPSHOT")).isTrue();
    }

    @Test
    void release_skips_snapshots_and_is_absent_when_all_are_snapshots() throws IOException {
        publish(List.of("1.0-SNAPSHOT", "2.0-SNAPSHOT"));
        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);
        assertThat(xml).contains("<latest>2.0-SNAPSHOT</latest>").doesNotContain("<release>");
    }

    @Test
    void checksums_match_the_xml_and_are_stable() throws IOException {
        publish(List.of("1.0", "2.0"));
        byte[] xml = metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow();
        byte[] sha1 = metadata.serve("/maven/org/example/lib/maven-metadata.xml.sha1").orElseThrow();
        assertThat(new String(sha1, StandardCharsets.UTF_8)).isEqualTo(sha1Hex(xml));
        assertThat(metadata.serve("/maven/org/example/lib/maven-metadata.xml.sha1").orElseThrow()).isEqualTo(sha1);
        assertThat(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow()).isEqualTo(xml);
    }

    @Test
    void an_unknown_coordinate_or_non_metadata_path_yields_nothing() throws IOException {
        publish(List.of("1.0"));
        assertThat(metadata.serve("/maven/org/example/missing/maven-metadata.xml")).isEmpty();
        assertThat(metadata.serve("/maven/org/example/lib/1.0/lib-1.0.jar")).isEmpty();
    }

    @Test
    void a_non_ascii_digit_version_does_not_crash_metadata_generation() throws IOException {
        // A version folder of Arabic-Indic digits is a numeric-looking token to Character.isDigit but not to
        // BigInteger; before the ASCII-only fix, comparing it threw NumberFormatException out of serve() (HTTP 500).
        publish(List.of("1.0", "١"));
        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);
        assertThat(xml).contains("<version>1.0</version>").contains("<version>١</version>");
    }

    @Test
    void a_metadata_checksum_sibling_is_not_mistaken_for_a_version() throws IOException {
        publish(List.of("1.0", "2.0"));
        // A .sha256 / .sha512 checksum sibling of maven-metadata.xml, deposited in the coordinate directory by a
        // client or cached there by the proxy, is not a version folder and must never be enumerated as a version.
        Publication publication = new Publication(store);
        publication.link("/maven/org/example/lib/maven-metadata.xml.sha256", "not-a-version");
        publication.link("/maven/org/example/lib/maven-metadata.xml.sha512", "not-a-version-either");

        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).contains("<version>1.0</version>").contains("<version>2.0</version>");
        assertThat(xml).doesNotContain("maven-metadata.xml.sha256").doesNotContain("maven-metadata.xml.sha512");
        assertThat(xml).contains("<release>2.0</release>");
    }

    private static boolean order(String xml, String... versions) {
        int previous = -1;
        for (String version : versions) {
            int index = xml.indexOf("<version>" + version + "</version>");
            if (index <= previous) {
                return false;
            }
            previous = index;
        }
        return true;
    }

    private static String sha1Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
