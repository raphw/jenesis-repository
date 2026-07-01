package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
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
 */
public final class MavenMetadata {

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
            if (!Character.isDigit(token.charAt(index))) {
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
