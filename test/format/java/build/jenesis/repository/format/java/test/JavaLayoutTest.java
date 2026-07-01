package build.jenesis.repository.format.java.test;

import build.jenesis.repository.format.java.JavaLayout;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared Java-layout primitives: {@code moduleName} reads a jar's declared module name (its
 * {@code Automatic-Module-Name}, else none for a plain jar, and nothing at all for a stream that is not a jar), and
 * {@code mavenCoordinate} parses a {@code /maven/...} request path into {@code [groupId, artifactId, version]},
 * joining a nested group with dots and rejecting a path that is not a full coordinate.
 */
class JavaLayoutTest {

    private static byte[] jar(Manifest manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = manifest == null
                ? new JarOutputStream(bytes)
                : new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry("com/example/Foo.class"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Manifest manifest(String automaticModuleName) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticModuleName != null) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticModuleName);
        }
        return manifest;
    }

    @Test
    void module_name_reads_the_automatic_module_name_from_the_manifest() throws IOException {
        byte[] jar = jar(manifest("com.example.auto"));
        assertThat(JavaLayout.moduleName(new ByteArrayInputStream(jar))).isEqualTo("com.example.auto");
    }

    @Test
    void module_name_is_null_for_a_plain_jar_without_a_module_or_automatic_name() throws IOException {
        assertThat(JavaLayout.moduleName(new ByteArrayInputStream(jar(manifest(null))))).isNull();
        assertThat(JavaLayout.moduleName(new ByteArrayInputStream(jar(null)))).isNull();
    }

    @Test
    void module_name_is_null_for_a_stream_that_is_not_a_jar() {
        assertThat(JavaLayout.moduleName(new ByteArrayInputStream("not a jar".getBytes(StandardCharsets.UTF_8))))
                .isNull();
    }

    @Test
    void maven_coordinate_splits_a_full_request_path_into_group_artifact_version() {
        assertThat(JavaLayout.mavenCoordinate("/maven/org/example/lib/1.0/lib-1.0.jar"))
                .containsExactly("org.example", "lib", "1.0");
    }

    @Test
    void maven_coordinate_joins_a_nested_group_with_dots() {
        assertThat(JavaLayout.mavenCoordinate("/maven/org/apache/commons/lang3/3.12.0/lang3-3.12.0.jar"))
                .containsExactly("org.apache.commons", "lang3", "3.12.0");
    }

    @Test
    void maven_coordinate_is_null_for_a_path_that_is_not_a_full_coordinate() {
        assertThat(JavaLayout.mavenCoordinate("/maven/org/example/lib")).isNull();
        assertThat(JavaLayout.mavenCoordinate("/maven/org/example")).isNull();
    }
}
