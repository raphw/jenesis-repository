package build.jenesis.repository.format.java;

import module java.base;

/**
 * Emits the {@code .pom} for a module cross-published under its Maven coordinate, so Maven clients can consume a
 * Jenesis module. The dependencies are read from the jar's module descriptor: each {@code requires} that names a
 * library module (not a JDK module, not the mandated {@code java.base}) becomes a {@code <dependency>} at the version
 * the descriptor recorded for it, or - when it recorded none - the version a {@link Versions} resolver supplies (the
 * coordinate's latest known version in the repository, itself proxied from a remote when configured, otherwise the
 * {@code LATEST} keyword). A jar with no module descriptor (a plain or automatic-module jar) declares no dependencies.
 */
public final class PomGenerator {

    /** Resolves the version to record for a required coordinate whose {@code requires} carried none. */
    @FunctionalInterface
    public interface Versions {
        String latest(String groupId, String artifactId);
    }

    public String pom(String groupId, String artifactId, String version, byte[] jar, Versions versions) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        xml.append("    <modelVersion>4.0.0</modelVersion>\n");
        xml.append("    <groupId>").append(groupId).append("</groupId>\n");
        xml.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        xml.append("    <version>").append(version).append("</version>\n");
        List<ModuleDescriptor.Requires> dependencies = JavaLayout.descriptor(jar)
                .map(PomGenerator::dependencies).orElseGet(List::of);
        if (!dependencies.isEmpty()) {
            xml.append("    <dependencies>\n");
            for (ModuleDescriptor.Requires requires : dependencies) {
                String[] coordinate = JavaLayout.coordinate(requires.name());
                String resolved = requires.compiledVersion().map(ModuleDescriptor.Version::toString)
                        .orElseGet(() -> versions.latest(coordinate[0], coordinate[1]));
                xml.append("        <dependency>\n");
                xml.append("            <groupId>").append(coordinate[0]).append("</groupId>\n");
                xml.append("            <artifactId>").append(coordinate[1]).append("</artifactId>\n");
                xml.append("            <version>").append(resolved).append("</version>\n");
                if (requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)) {
                    xml.append("            <optional>true</optional>\n");
                }
                xml.append("        </dependency>\n");
            }
            xml.append("    </dependencies>\n");
        }
        xml.append("</project>\n");
        return xml.toString();
    }

    /** The library dependencies a descriptor declares: its {@code requires}, minus the mandated {@code java.base} and
     *  the other JDK modules (which are not Maven artifacts). */
    private static List<ModuleDescriptor.Requires> dependencies(ModuleDescriptor descriptor) {
        List<ModuleDescriptor.Requires> dependencies = new ArrayList<>();
        for (ModuleDescriptor.Requires requires : descriptor.requires()) {
            if (!requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.MANDATED)
                    && !requires.name().startsWith("java.")
                    && !requires.name().startsWith("jdk.")) {
                dependencies.add(requires);
            }
        }
        return dependencies;
    }
}
