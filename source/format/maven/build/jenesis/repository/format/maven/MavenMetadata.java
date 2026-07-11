package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Generates the artifact-level {@code maven-metadata.xml} on read, from the version folders published under a
 * coordinate, instead of persisting and rewriting a single mutable object. Because the version list is derived
 * from per-version pointers (distinct, append-only keys) rather than a read-modify-write monolith, two nodes
 * publishing different versions never touch the same object - which is what lets a content-addressed store
 * replicate write-anywhere across regions without losing a version from the index.
 *
 * The {@code <release>} is the highest non-snapshot version and {@code <latest>} the highest overall, ordered by
 * a Maven-style version comparison; {@code <lastUpdated>} is intentionally omitted so the rendered bytes are a
 * pure function of the version set and the checksum a client cached still matches a re-fetch. The matching
 * {@code .sha1} / {@code .md5} are computed from those same bytes.
 *
 * <p>Deriving is no longer the default (W5.12): a {@code maven-metadata.xml} a client publishes is stored verbatim
 * like any artifact and served back byte-for-byte, so a publisher-authored document round-trips untouched. The
 * derivation above is the opt-in {@link #COMPUTE_SETTING} computation instead - {@link #computed} reconciles a stored
 * document's version list against the folders (leaving every other field verbatim) and falls back to a full
 * {@link #serve derivation} only for a coordinate no client ever uploaded (the importer / batch case).
 */
public final class MavenMetadata {

    /** The bare setting key (under {@code jenesis.repository.}) that opts a deployment into computing the served
     *  artifact-level {@code maven-metadata.xml} rather than serving the stored bytes verbatim - default off, read
     *  off the exchange so the free format needs no settings dependency. */
    public static final String COMPUTE_SETTING = "maven-metadata-compute";

    private final ArtifactStore store;

    public MavenMetadata(ArtifactStore store) {
        this.store = store;
    }

    /** Whether this request path is an artifact-level {@code maven-metadata.xml} or one of its checksums. */
    public static boolean isMetadataRequest(String requestPath) {
        return requestPath.startsWith("/maven/")
                && (requestPath.endsWith("/maven-metadata.xml")
                || requestPath.endsWith("/maven-metadata.xml.sha1")
                || requestPath.endsWith("/maven-metadata.xml.md5"));
    }

    /**
     * The bytes for a metadata request under the opt-in {@link #COMPUTE_SETTING} computation, or empty when the
     * default verbatim serve should stand (the caller then streams the stored pointer, a 404 when none). For the
     * {@code maven-metadata.xml} itself: a stored document has only its {@code <versions>} list reconciled against the
     * stored version folders - every other field the publisher wrote (latest, release, lastUpdated, plugin prefixes,
     * snapshot sections) is preserved verbatim, and a document with no {@code <versions>} list (a version-level
     * SNAPSHOT document) passes through untouched; a coordinate with no stored document falls back to the full
     * {@link #serve derivation} (the importer / batch case). A checksum is computed from the served bytes <em>only</em>
     * when the document was authored here (a reconciled or derived document); an unchanged pass-through returns empty
     * so the caller serves the publisher's own stored checksum byte-for-byte.
     */
    public Optional<byte[]> computed(String requestPath) throws IOException {
        if (!isMetadataRequest(requestPath)) {
            return Optional.empty();
        }
        if (requestPath.endsWith(".sha1") || requestPath.endsWith(".md5")) {
            String documentPath = requestPath.substring(0, requestPath.lastIndexOf('.'));
            Optional<byte[]> document = computedDocument(documentPath);
            if (document.isEmpty()) {
                return Optional.empty();
            }
            Optional<byte[]> stored = storedBytes(documentPath);
            if (stored.isPresent() && Arrays.equals(stored.get(), document.get())) {
                // The document is served verbatim, so its stored checksum is authoritative - never re-derived.
                return Optional.empty();
            }
            String algorithm = requestPath.endsWith(".sha1") ? "SHA-1" : "MD5";
            return Optional.of(hex(algorithm, document.get()).getBytes(StandardCharsets.UTF_8));
        }
        return computedDocument(requestPath);
    }

    /** The served artifact-level document under the computation flag: a stored document with its versions reconciled
     *  (or unchanged when it carries none or lists them all), else the full derivation for a coordinate that never had
     *  one uploaded. Empty when neither a stored document nor any published version exists (a 404). */
    private Optional<byte[]> computedDocument(String documentPath) throws IOException {
        Optional<byte[]> stored = storedBytes(documentPath);
        if (stored.isPresent()) {
            return Optional.of(reconcileVersions(documentPath, stored.get()));
        }
        return serve(documentPath);
    }

    /** The stored bytes a metadata (or checksum) path currently points at, or empty when nothing is published there. */
    private Optional<byte[]> storedBytes(String requestPath) throws IOException {
        Optional<String> key = new Publication(store).located(requestPath);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        store.read(key.get(), buffer);
        return Optional.of(buffer.toByteArray());
    }

    /**
     * Reconcile a stored document's {@code <versions>} list against the coordinate's stored version folders, leaving
     * every byte outside {@code <versions>...</versions>} exactly as the publisher wrote it. A document with no
     * {@code <versions>} element (a version-level SNAPSHOT document, whose versions live under
     * {@code <snapshotVersions>}) is returned untouched, and so is one that already lists every stored folder - only a
     * genuinely missing version rewrites the element, adding it in Maven version order.
     */
    private byte[] reconcileVersions(String documentPath, byte[] storedXml) {
        String xml = new String(storedXml, StandardCharsets.UTF_8);
        int open = xml.indexOf("<versions>");
        int contentStart;
        int contentEnd;
        boolean selfClosed;
        if (open >= 0) {
            contentStart = open + "<versions>".length();
            contentEnd = xml.indexOf("</versions>", contentStart);
            if (contentEnd < 0) {
                return storedXml;
            }
            selfClosed = false;
        } else {
            open = xml.indexOf("<versions/>");
            if (open < 0) {
                return storedXml;
            }
            contentStart = open + "<versions/>".length();
            contentEnd = contentStart;
            selfClosed = true;
        }
        List<String> folders = versions(coordinatePath(documentPath));
        Set<String> existing = new HashSet<>(listedVersions(xml.substring(contentStart, contentEnd)));
        if (existing.containsAll(folders)) {
            return storedXml;
        }
        SequencedSet<String> union = new LinkedHashSet<>(existing);
        union.addAll(folders);
        List<String> reconciled = new ArrayList<>(union);
        reconciled.sort(MavenMetadata::compareVersions);
        String baseIndent = indentBefore(xml, open);
        StringBuilder rebuilt = new StringBuilder(xml.length() + reconciled.size() * 32);
        rebuilt.append(xml, 0, open).append("<versions>");
        for (String version : reconciled) {
            rebuilt.append('\n').append(baseIndent).append("  <version>").append(version).append("</version>");
        }
        rebuilt.append('\n').append(baseIndent).append("</versions>");
        rebuilt.append(xml, selfClosed ? contentStart : contentEnd + "</versions>".length(), xml.length());
        return rebuilt.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The {@code <version>} texts a {@code <versions>} block already lists, in document order. */
    private static List<String> listedVersions(String inner) {
        List<String> listed = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = inner.indexOf("<version>", cursor);
            if (start < 0) {
                return listed;
            }
            int end = inner.indexOf("</version>", start);
            if (end < 0) {
                return listed;
            }
            listed.add(inner.substring(start + "<version>".length(), end).trim());
            cursor = end + "</version>".length();
        }
    }

    /** The whitespace indentation of the line the element at {@code index} sits on, or empty when it does not start a
     *  line (so a reconciled block is indented consistently with the document). */
    private static String indentBefore(String xml, int index) {
        int lineStart = xml.lastIndexOf('\n', index) + 1;
        String indent = xml.substring(lineStart, index);
        return indent.isBlank() ? indent : "";
    }

    private static String coordinatePath(String requestPath) {
        String body = requestPath.substring("/maven/".length());
        return body.substring(0, body.lastIndexOf("/maven-metadata.xml"));
    }

    /**
     * The bytes for a metadata request - the XML derived from the coordinate's published version folders, or its
     * SHA-1 / MD5 checksum - or empty if the path is not a metadata request or the coordinate has no versions.
     */
    public Optional<byte[]> serve(String requestPath) {
        if (!isMetadataRequest(requestPath)) {
            return Optional.empty();
        }
        String body = requestPath.substring("/maven/".length());
        String coordinatePath = body.substring(0, body.lastIndexOf("/maven-metadata.xml"));
        int slash = coordinatePath.lastIndexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        String artifactId = coordinatePath.substring(slash + 1);
        String groupId = coordinatePath.substring(0, slash).replace('/', '.');
        List<String> versions = versions(coordinatePath);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        byte[] xml = metadata(groupId, artifactId, versions);
        if (requestPath.endsWith(".sha1")) {
            return Optional.of(hex("SHA-1", xml).getBytes(StandardCharsets.UTF_8));
        }
        if (requestPath.endsWith(".md5")) {
            return Optional.of(hex("MD5", xml).getBytes(StandardCharsets.UTF_8));
        }
        return Optional.of(xml);
    }

    private List<String> versions(String coordinatePath) {
        List<String> versions = new ArrayList<>();
        for (String child : store.list("publish/maven/" + coordinatePath)) {
            if (child.equals("maven-metadata.xml") || child.endsWith(".sha1") || child.endsWith(".md5")
                    || child.endsWith(".asc")) {
                continue;
            }
            versions.add(child);
        }
        versions.sort(MavenMetadata::compareVersions);
        return versions;
    }

    private static byte[] metadata(String groupId, String artifactId, List<String> versions) {
        String release = null;
        for (String version : versions) {
            if (!version.endsWith("-SNAPSHOT")) {
                release = version;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("metadata");
            element(writer, "groupId", groupId);
            element(writer, "artifactId", artifactId);
            writer.writeStartElement("versioning");
            element(writer, "latest", versions.getLast());
            if (release != null) {
                element(writer, "release", release);
            }
            writer.writeStartElement("versions");
            for (String version : versions) {
                element(writer, "version", version);
            }
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static void element(XMLStreamWriter writer, String name, String text) throws XMLStreamException {
        writer.writeStartElement(name);
        writer.writeCharacters(text);
        writer.writeEndElement();
    }

    private static String hex(String algorithm, byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Maven-style version order: numeric runs compared as numbers, qualifiers ranked (alpha < ... < snapshot < release < sp). */
    static int compareVersions(String left, String right) {
        List<String> a = tokenize(left);
        List<String> b = tokenize(right);
        int length = Math.max(a.size(), b.size());
        for (int index = 0; index < length; index++) {
            int comparison = compareToken(
                    index < a.size() ? a.get(index) : null,
                    index < b.size() ? b.get(index) : null);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int compareToken(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -signum(b);
        }
        if (b == null) {
            return signum(a);
        }
        boolean numericA = isNumeric(a), numericB = isNumeric(b);
        if (numericA && numericB) {
            return new BigInteger(a).compareTo(new BigInteger(b));
        }
        if (numericA) {
            return 1;
        }
        if (numericB) {
            return -1;
        }
        int rankA = qualifierRank(a), rankB = qualifierRank(b);
        if (rankA != rankB) {
            return Integer.compare(rankA, rankB);
        }
        return rankA == UNKNOWN_QUALIFIER ? a.compareTo(b) : 0;
    }

    /** The sign of a token against its empty baseline: a number against zero, a qualifier against release. */
    private static int signum(String token) {
        return isNumeric(token)
                ? new BigInteger(token).signum()
                : Integer.compare(qualifierRank(token), RELEASE_QUALIFIER);
    }

    private static final int RELEASE_QUALIFIER = 6;
    private static final int UNKNOWN_QUALIFIER = 8;

    private static int qualifierRank(String qualifier) {
        return switch (qualifier) {
            case "alpha", "a" -> 1;
            case "beta", "b" -> 2;
            case "milestone", "m" -> 3;
            case "rc", "cr" -> 4;
            case "snapshot" -> 5;
            case "", "ga", "final", "release" -> RELEASE_QUALIFIER;
            case "sp" -> 7;
            default -> UNKNOWN_QUALIFIER;
        };
    }

    private static boolean isNumeric(String token) {
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            // ASCII digits only: a numeric token is parsed with new BigInteger, which rejects the non-ASCII digits
            // Character.isDigit would accept (an Arabic-Indic version folder would then throw a 500 out of serve).
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return !token.isEmpty();
    }

    private static List<String> tokenize(String version) {
        String lower = version.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Boolean digit = null;
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (character == '.' || character == '-' || character == '_') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                digit = null;
                continue;
            }
            boolean isDigit = Character.isDigit(character);
            if (digit != null && isDigit != digit && current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            current.append(character);
            digit = isDigit;
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
