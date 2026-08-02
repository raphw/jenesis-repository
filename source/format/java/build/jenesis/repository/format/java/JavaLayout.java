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

    /** The most decompressed bytes read from a single jar entry the inspection materialises (the manifest and the
     *  {@code module-info.class}). Both are small metadata - a few KB - so a far larger entry is a decompression bomb:
     *  a crafted jar whose tiny stored blob inflates a high-ratio {@code MANIFEST.MF} to gigabytes would otherwise be
     *  buffered whole in heap here (an OOM DoS on the shared JVM on every jar publish). Over the cap the entry is
     *  ignored (treated as no module) rather than inflated unbounded. */
    private static final int MAX_METADATA_ENTRY = 1 << 20;

    /** The module name a jar declares - its {@code module-info} name, or its {@code Automatic-Module-Name} - or null
     *  when it carries neither (a plain jar, not a module). The jar is walked as a stream (typically opened back from
     *  storage after the blob was streamed in), so the artifact is never buffered whole in memory; the only entries read
     *  into heap - the manifest and {@code module-info.class} - are each size-capped ({@link #MAX_METADATA_ENTRY}), so a
     *  decompression bomb in either cannot inflate unbounded. Other entries are streamed past, never materialised. */
    public static String moduleName(InputStream jar) {
        try (ZipInputStream in = new ZipInputStream(jar)) {
            String automatic = null;
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.getName().equals("module-info.class")) {
                    byte[] descriptor = bounded(in);
                    if (descriptor != null) {
                        return ModuleDescriptor.read(ByteBuffer.wrap(descriptor)).name();
                    }
                } else if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    byte[] bytes = bounded(in);
                    if (bytes != null) {
                        automatic = new Manifest(new ByteArrayInputStream(bytes))
                                .getMainAttributes().getValue("Automatic-Module-Name");
                    }
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

    /** The current zip entry's decompressed bytes, or null once they exceed {@link #MAX_METADATA_ENTRY} - so a
     *  high-ratio decompression bomb is abandoned at the cap instead of inflated whole into heap. */
    private static byte[] bounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = in.read(buffer)) != -1; ) {
            total += read;
            if (total > MAX_METADATA_ENTRY) {
                return null;                                    // over the cap: a bomb, ignore this entry
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
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
