package build.jenesis.repository.importer.maven;

import module java.base;

/**
 * A streaming reader for the legacy Nexus / Maven-Indexer repository index that Nexus-style servers publish under
 * {@code .index/} - a pure-JDK port of the jenesis-modules crawler's chunk reader, so no indexer library (none has a
 * stable module name) and nothing buffered: the file is a GZIP stream of a one-byte format version, an eight-byte
 * timestamp, then records of an {@code int} field count followed per field by a flag byte, a modified-UTF-8 name, an
 * {@code int} length and the value bytes (themselves GZIP-compressed when flag {@code 0x08} is set). A record's
 * {@code u} (uinfo) field carries the pipe-delimited coordinate {@code group|artifact|version|classifier|extension}
 * ({@code NA} for absent), its {@code i} (info) field the packaging and, at position six, a fallback extension;
 * {@code del} marks a tombstone. Field caps guard against a corrupted or hostile stream.
 */
final class RepositoryIndex implements Closeable {

    /** The index descriptor whose presence advertises a published index. */
    static final String PROPERTIES = ".index/nexus-maven-repository-index.properties";

    /** The full index chunk (the incremental chunks only matter to a mirror staying fresh, not to a one-shot import). */
    static final String FULL = ".index/nexus-maven-repository-index.gz";

    private static final int FLAG_COMPRESSED = 0x08;
    private static final int MAX_FIELD_COUNT = 1024;
    private static final int MAX_FIELD_LENGTH = 16 * 1024 * 1024;
    private static final String NOT_AVAILABLE = "NA";

    /** Packagings that are their own file extension; any other (maven-plugin, bundle, ejb, ...) packs a jar. */
    private static final Set<String> LITERAL_PACKAGINGS = Set.of(
            "jar", "pom", "war", "ear", "aar", "zip", "tar.gz", "so", "dll", "nbm", "hpi", "rar", "swc", "gem");

    private final DataInputStream input;

    /** Open over the raw (still GZIP-compressed) index bytes and consume the header. */
    RepositoryIndex(InputStream stream) throws IOException {
        input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(stream)));
        input.readByte();   // the format version
        input.readLong();   // the creation timestamp
    }

    /** The next record's fields by name, or {@code null} at the end of the index. */
    Map<String, String> next() throws IOException {
        int fields;
        try {
            fields = input.readInt();
        } catch (EOFException end) {
            return null;
        }
        if (fields < 0 || fields > MAX_FIELD_COUNT) {
            throw new IOException("Implausible index record field count " + fields + " - the stream is corrupted");
        }
        Map<String, String> record = new HashMap<>();
        for (int field = 0; field < fields; field++) {
            byte flag = input.readByte();
            String name = input.readUTF();
            int length = input.readInt();
            if (length < 0 || length > MAX_FIELD_LENGTH) {
                throw new IOException("Implausible index field length " + length + " for '" + name
                        + "' - the stream is corrupted");
            }
            byte[] value = input.readNBytes(length);
            if (value.length < length) {
                throw new EOFException("Truncated index field '" + name + "'");
            }
            record.put(name, (flag & FLAG_COMPRESSED) != 0
                    ? new String(new GZIPInputStream(new ByteArrayInputStream(value)).readAllBytes(), StandardCharsets.UTF_8)
                    : new String(value, StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    /** The coordinate a record describes, or {@code null} for a tombstone, a descriptor record, or a record whose
     *  fields would not form a safe URL path. The extension resolves as uinfo's, then info's position six, then the
     *  packaging - and a classifier-less record whose resolved extension is no real packaging is rewritten to
     *  {@code jar}, since the indexer stamps that record with whatever sidecar it processed last. */
    static Gav coordinate(Map<String, String> record) {
        String uinfo = record.get("u");
        if (uinfo == null || record.containsKey("del")) {
            return null;
        }
        String[] u = uinfo.split("\\|", -1);
        if (u.length < 3 || u[0].isEmpty() || u[1].isEmpty() || u[2].isEmpty()) {
            return null;
        }
        String classifier = u.length > 3 && !NOT_AVAILABLE.equals(u[3]) && !u[3].isEmpty() ? u[3] : null;
        String extension = u.length > 4 && !NOT_AVAILABLE.equals(u[4]) && !u[4].isEmpty() ? u[4] : null;
        String packaging = null;
        String info = record.get("i");
        if (info != null) {
            String[] i = info.split("\\|", -1);
            if (i.length > 0 && !NOT_AVAILABLE.equals(i[0]) && !i[0].isEmpty()) {
                packaging = i[0];
            }
            if (extension == null && i.length > 6 && !NOT_AVAILABLE.equals(i[6]) && !i[6].isEmpty()) {
                extension = i[6];
            }
        }
        if (extension == null) {
            extension = packaging == null ? "jar" : packaging;
        }
        if (classifier == null && !LITERAL_PACKAGINGS.contains(extension)) {
            extension = "jar";
        }
        if (!safeDotted(u[0]) || !safe(u[1]) || !safe(u[2]) || classifier != null && !safe(classifier) || !safeDotted(extension)) {
            return null;
        }
        return new Gav(u[0], u[1], u[2], classifier, extension);
    }

    /** Whether a coordinate part is safe to splice into a URL path: printable ASCII with no separator, traversal,
     *  escape or query character - anything else is skipped rather than fetched. */
    static boolean safe(String part) {
        if (part.isEmpty() || part.equals(".") || part.equals("..")) {
            return false;
        }
        for (int index = 0; index < part.length(); index++) {
            char character = part.charAt(index);
            if (character <= ' ' || character > '~' || "/\\%?#\"<>{}|^`".indexOf(character) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** A group turns its dots into path separators (and an extension may be dotted, {@code tar.gz}), so every
     *  dot-separated piece must itself be safe - no empty or traversal segments reach the URL. */
    private static boolean safeDotted(String value) {
        for (String part : value.split("\\.", -1)) {
            if (!safe(part)) {
                return false;
            }
        }
        return true;
    }

    /** The file extension a pom's packaging names. */
    static String extension(String packaging) {
        return LITERAL_PACKAGINGS.contains(packaging) ? packaging : "jar";
    }

    /** A decoded index record: dotted group, artifact, version, optional classifier and resolved extension. */
    record Gav(String group, String artifact, String version, String classifier, String extension) {

        /** The coordinate's directory: {@code <group-path>/<artifact>}. */
        String artifactPath() {
            return group.replace('.', '/') + "/" + artifact;
        }

        /** The record's file within the Maven layout. */
        String path() {
            return artifactPath() + "/" + version + "/" + artifact + "-" + version
                    + (classifier == null ? "" : "-" + classifier) + "." + extension;
        }

        /** The pom a classifier-less record implies (the index carries no separate pom record for a jar), or
         *  {@code null} for a classifier sidecar, whose pom belongs to its primary. */
        String pomPath() {
            return classifier == null ? artifactPath() + "/" + version + "/" + artifact + "-" + version + ".pom" : null;
        }
    }
}
