package build.jenesis.repository.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.reflect.AccessFlag;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import build.jenesis.repository.format.java.PomGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The POM generated for a cross-published module carries its dependencies, read from the jar's module descriptor: a
 * {@code requires} that recorded a version keeps it, one that did not is resolved (here to a fixed stand-in for the
 * repository's latest), a {@code requires static} is optional, and the mandated {@code java.base} and the JDK modules
 * are not dependencies. A jar with no descriptor declares none.
 */
class PomGeneratorTest {

    @Test
    void computes_dependencies_from_the_module_descriptor() throws IOException {
        byte[] jar = modularJar("com.acme.app", builder -> builder
                .requires(ModuleDesc.of("org.apache.commons.lang3"), 0, "3.14.0")
                .requires(ModuleDesc.of("com.google.guava"), 0, null)
                .requires(ModuleDesc.of("jakarta.annotation"), AccessFlag.STATIC_PHASE.mask(), "3.0.0")
                .requires(ModuleDesc.of("java.base"), AccessFlag.MANDATED.mask(), null)
                .requires(ModuleDesc.of("java.net.http"), 0, null));

        String pom = new PomGenerator().pom("com.acme", "app", "1.0", jar, (group, artifact) -> "LATEST");

        assertThat(pom).as("a requires with a recorded version keeps it")
                .contains("<groupId>org.apache.commons</groupId>")
                .contains("<artifactId>lang3</artifactId>")
                .contains("<version>3.14.0</version>");
        assertThat(pom).as("a requires without a version is resolved").contains("<artifactId>guava</artifactId>")
                .contains("<version>LATEST</version>");
        assertThat(pom).as("a requires static is optional")
                .contains("<artifactId>annotation</artifactId>").contains("<optional>true</optional>");
        assertThat(pom).as("the mandated java.base and the JDK modules are not dependencies")
                .doesNotContain("java.base").doesNotContain("java.net.http").doesNotContain("net.http");
    }

    @Test
    void a_jar_without_a_module_descriptor_declares_no_dependencies() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("com/acme/App.class"));
            jar.closeEntry();
        }
        assertThat(new PomGenerator().pom("com.acme", "app", "1.0", out.toByteArray(), (group, artifact) -> "LATEST"))
                .doesNotContain("<dependencies>");
    }

    private static byte[] modularJar(String moduleName, java.util.function.Consumer<ModuleAttribute.ModuleAttributeBuilder> requires)
            throws IOException {
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(moduleName), requires));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("module-info.class"));
            jar.write(moduleInfo);
            jar.closeEntry();
        }
        return out.toByteArray();
    }
}
