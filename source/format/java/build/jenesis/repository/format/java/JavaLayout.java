package build.jenesis.repository.format.java;

import module java.base;

/**
 * The primitives the Maven layout needs to cross-publish into the Jenesis module layout: reading the module name a jar
 * declares, and parsing a Maven request path into its coordinate. These live in the shared Java-layout module so the
 * module-descriptor reading and the coordinate convention sit in one place rather than in the core.
 */
public final class JavaLayout {

    private JavaLayout() {
    }

    /** The module name a jar declares - its {@code module-info} name, or its {@code Automatic-Module-Name} - or null
     *  when it carries neither (a plain jar, not a module). The jar is read as a stream (typically opened back from
     *  storage after the blob was streamed in), so the artifact is never buffered whole in memory to be inspected. */
    public static String moduleName(InputStream jar) {
        try (JarInputStream in = new JarInputStream(jar)) {
            String automatic = in.getManifest() == null ? null
                    : in.getManifest().getMainAttributes().getValue("Automatic-Module-Name");
            for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
                if (entry.getName().equals("module-info.class")) {
                    return ModuleDescriptor.read(in).name();
                }
            }
            // A module-info name is JVM-validated by read(); an Automatic-Module-Name is a raw manifest string that
            // becomes a /module/<name>/ store key, so validate it is a legal module name first - a crafted value (a
            // '/'- or '..'-laced or empty name) is treated as no module rather than reaching a pointer key.
            return automatic == null ? null : validModuleName(automatic);
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /** The name if it is a legal Java module name (dot-separated Java identifiers), else null. Uses the JDK's own
     *  module-name validation so the rule matches exactly what a real module name may contain. */
    private static String validModuleName(String name) {
        try {
            ModuleDescriptor.newAutomaticModule(name);
            return name;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /** The {@code [groupId, artifactId, version]} of a {@code /maven/...} request path, or null when it is not a full
     *  coordinate (a group directory, a checksum root). */
    public static String[] mavenCoordinate(String requestPath) {
        String[] segments = requestPath.substring("/maven/".length()).split("/");
        if (segments.length < 4) {
            return null;
        }
        return new String[]{
                String.join(".", Arrays.copyOf(segments, segments.length - 3)),
                segments[segments.length - 3],
                segments[segments.length - 2]};
    }
}
