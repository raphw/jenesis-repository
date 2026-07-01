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
     *  when it carries neither (a plain jar, not a module). */
    public static String moduleName(byte[] jar) {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            String automatic = in.getManifest() == null ? null
                    : in.getManifest().getMainAttributes().getValue("Automatic-Module-Name");
            for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
                if (entry.getName().equals("module-info.class")) {
                    return ModuleDescriptor.read(in).name();
                }
            }
            return automatic;
        } catch (IOException | RuntimeException _) {
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
