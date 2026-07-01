package build.jenesis.repository.format.java;

import module java.base;

/**
 * The primitives the Maven and Jenesis layouts share: reading the module name a jar declares, and mapping between a
 * module name and a Maven coordinate. The two layout formats build on these so the module-descriptor parsing and the
 * coordinate convention live in one place rather than being duplicated (or, as before, sitting in the core).
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

    /** The {@code [moduleName, version]} of a {@code /module/...} or {@code /artifact/...} request path, or null. */
    public static String[] moduleReference(String requestPath) {
        String tail = requestPath.startsWith("/module/")
                ? requestPath.substring("/module/".length())
                : requestPath.substring("/artifact/".length());
        String[] segments = tail.split("/");
        return segments.length < 3 ? null : new String[]{segments[0], segments[1]};
    }

    /** The Maven {@code [groupId, artifactId]} derived from a module name: its last dot splits group from artifact,
     *  a dotless name is both. */
    public static String[] coordinate(String moduleName) {
        int dot = moduleName.lastIndexOf('.');
        return dot < 0
                ? new String[]{moduleName, moduleName}
                : new String[]{moduleName.substring(0, dot), moduleName.substring(dot + 1)};
    }
}
