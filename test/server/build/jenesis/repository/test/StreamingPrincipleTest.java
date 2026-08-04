package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces engineering principle <b>&sect;1 &mdash; "stream, never buffer"</b> structurally, at build time, across the
 * FREE module sources - the free counterpart of the enterprise {@code StreamingPrincipleTest} ratchet, scoped to this
 * repository's {@code source/} tree (maven, oci, raw, java, jenesis, the store backends, proxy, importer).
 *
 * <p>&sect;1: <i>no whole-blob read ({@code readAllBytes}/{@code toByteArray}) of an <b>artifact</b> on any upload /
 * download / proxy path &mdash; only a small index/metadata blob may be fully materialised. A hosted publish is
 * store-then-gate (hash-on-write into the CAS, gate a descriptor, not a {@code byte[]}); a proxy uses the streaming
 * {@code download} path; heap stays bounded under a &gt;2&nbsp;GiB upload.</i>
 *
 * <p>Behavioural streaming tests prove the <em>existing</em> hot paths stream; none of them fails the build when a
 * <em>new</em> code path slurps a whole artifact into heap. This is that structural guard: it scans the free source tree
 * for whole-blob materialisations and fails the build on any that is not an allowlisted bounded metadata/index/config
 * read - the {@code EnumerationScreenPrincipleTest}/{@code ConfigPrincipleTest} mould (a deterministic source scan with a
 * justified allowlist), applied to the streaming principle.
 *
 * <h2>What is flagged (the matcher)</h2>
 * Over the comment-stripped source of every {@code .java} under {@code source/}, a hit is any of:
 * <ol>
 *   <li><b>a whole-stream / whole-file read</b> &mdash; {@code .readAllBytes(} (an {@code InputStream} or channel
 *       drained into one array, including {@code Files.readAllBytes}), {@code .readAllLines(}, {@code Files.readString(},
 *       or a library slurp {@code IOUtils.toByteArray(} / {@code ByteStreams.toByteArray(}; and</li>
 *   <li><b>a {@code ByteArrayOutputStream}-accumulate-an-input</b> &mdash; a store/stream copied whole into a heap
 *       buffer: a call {@code x.read(&hellip;, baos)} / {@code x.transferTo(baos)} / {@code x.copy(&hellip;, baos)} /
 *       {@code x.readBodyData(baos)} whose <em>last</em> argument is a local whose nearest preceding declaration is
 *       {@code ByteArrayOutputStream}. An artifact served through the {@code download} path copies into the
 *       <em>response</em> {@code OutputStream} ({@code try (OutputStream out = exchange.respond(&hellip;)) store.read(key,
 *       out)}), whose declared type is not {@code ByteArrayOutputStream}, so it is <em>not</em> flagged; only a copy into
 *       a heap buffer is.</li>
 * </ol>
 *
 * <h2>The allowlist</h2>
 * Every current hit is a <b>bounded small blob</b>, not an artifact body: a versioned-object store primitive (a publish
 * pointer / withheld marker / tag / config value) or a {@code maven-metadata.xml}-class index document. Each is listed in
 * {@link #ALLOWLIST} keyed by <b>{@code SimpleClassName} + a stable code substring</b> (never a line number) with a
 * one-line justification derived from reading the actual code. A hit that matches no allowlist entry <b>fails the
 * build</b>, naming the file, line and exact call, and pointing the author at &sect;1: stream it, or - if it genuinely is
 * a bounded metadata/index/config read - add an allowlist entry <em>with</em> a justification.
 *
 * <h2>Scope &amp; honest limitations</h2>
 * The scan covers all of the free {@code source/} (excludes {@code test/} and {@code target/} simply by walking only
 * {@code source/}). Like its enterprise twin it is a text scan matching the idioms above; a hand-rolled
 * {@code while ((n = in.read(chunk)) >= 0) baos.write(chunk, 0, n)} copy loop is <em>not</em> matched (modern code uses
 * {@code transferTo}, which is). The value it delivers is that a new {@code someArtifactStream.readAllBytes()} /
 * {@code toByteArray()} / a fresh {@code store.read(artifactKey, aByteArrayOutputStream)} on an upload/download/proxy path
 * is caught the moment it is added, unless the author consciously justifies it as bounded metadata.
 */
class StreamingPrincipleTest {

    /**
     * A justified whole-read: a bounded small metadata / index / config / versioned-object blob, never an artifact body.
     * Keyed on {@code SimpleClassName} + a stable code substring so the entry survives unrelated line shifts; a hit is
     * allowed when its class matches and the flagged source line <em>contains</em> the substring.
     */
    private record Allow(String className, String anchor, String justification) {}

    private static final List<Allow> ALLOWLIST = allowlist();

    private static List<Allow> allowlist() {
        List<Allow> a = new ArrayList<>();

        // --- Store readVersioned primitives: a small VERSIONED object, never an artifact body. readVersioned is the
        //     compare-and-set metadata read - it backs publish/<path> pointers, withheld/<hash> and gc markers,
        //     oci/<name>/tags/<tag>, and config keys. A large artifact is never a versioned object: it is written
        //     content-addressed (writeBlob) and served by the streaming read/open path, so these whole reads are
        //     bounded small metadata by the store contract itself. ---
        a.add(new Allow("FilesystemArtifactStore", "Files.readAllBytes(path)",
                "readVersioned reads one small versioned object off disk (a publish pointer / withheld or gc marker / "
                        + "oci tag / config value), never an artifact body - artifacts stream via read()/open()"));
        a.add(new Allow("GcsArtifactStore", "in.readAllBytes()",
                "readVersioned reads one small versioned object off the GCS XML endpoint (pointer/marker/tag/config "
                        + "value), never an artifact body - artifacts stream via the read path"));
        a.add(new Allow("S3ArtifactStore", "in.readAllBytes()",
                "readVersioned reads one small versioned object off S3 (pointer/marker/tag/config value), never an "
                        + "artifact body - artifacts stream via the read path"));

        // --- Generated/stored index metadata parsed on the read path (the metadata read A7 allows) ---
        a.add(new Allow("MavenMetadata", "store.read(key.get(), buffer)",
                "reads the stored maven-metadata.xml document to reconcile its <versions>/<latest>/<release> against "
                        + "the published version folders - a small index-metadata document, not an artifact (jars "
                        + "stream via the download path)"));

        return List.copyOf(a);
    }

    /** One flagged whole-read: the class it lives in, its source location, the exact matched call, and the flagged
     *  (comment-stripped, whitespace-collapsed) source line the allowlist anchors match against. */
    private record Hit(String className, String location, String call, String codeLine) {}

    @Test
    void every_whole_blob_read_is_an_allowlisted_bounded_metadata_read() throws IOException {
        List<Hit> hits = scan(sourceRoot());
        assertThat(hits)
                .as("the source scan found whole-blob reads - the streaming-principle check is not vacuous")
                .isNotEmpty();

        List<String> violations = hits.stream()
                .filter(h -> ALLOWLIST.stream().noneMatch(a ->
                        a.className().equals(h.className()) && h.codeLine().contains(a.anchor())))
                .map(h -> "  - " + h.location() + "  [" + h.call() + "]  " + h.codeLine())
                .sorted()
                .toList();

        assertThat(violations)
                .as("these whole-blob reads violate the stream-never-buffer principle (PRINCIPLES.md #1): an "
                        + "artifact must never be materialised whole on an upload/download/proxy path. Stream it "
                        + "instead - a hosted publish is store-then-gate (hash-on-write into the CAS, gate a "
                        + "descriptor, not a byte[]); a proxy uses the streaming download path. If this read is "
                        + "genuinely a bounded small metadata/index/config blob (a maven-metadata/versioned-object "
                        + "document), add it to ALLOWLIST with a one-line justification.%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void the_allowlist_stays_live() throws IOException {
        List<Hit> hits = scan(sourceRoot());
        List<String> dead = ALLOWLIST.stream()
                .filter(a -> hits.stream().noneMatch(h ->
                        a.className().equals(h.className()) && h.codeLine().contains(a.anchor())))
                .map(a -> "  - " + a.className() + "#" + a.anchor())
                .sorted()
                .toList();
        assertThat(dead)
                .as("these ALLOWLIST entries no longer match any flagged read - the code changed; remove or update "
                        + "the entry so the allowlist tracks the source and cannot silently mask a future violation")
                .isEmpty();
    }

    // --- the scan -------------------------------------------------------------------------------------------------

    private static final List<Pattern> DIRECT_READS = List.of(
            Pattern.compile("\\.readAllBytes\\s*\\("),
            Pattern.compile("\\.readAllLines\\s*\\("),
            Pattern.compile("\\bFiles\\.readString\\s*\\("),
            Pattern.compile("\\b(?:IOUtils|ByteStreams)\\.toByteArray\\s*\\("));

    /** A copy-into-a-heap-buffer call whose LAST argument is resolved (below) to a {@code ByteArrayOutputStream}. */
    private static final Pattern FILL_CALL = Pattern.compile("\\.(read|transferTo|copy|readBodyData)\\s*\\(");

    private static List<Hit> scan(Path sourceRoot) throws IOException {
        List<Hit> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(StreamingPrincipleTest::isJava)::iterator) {
                String className = file.getFileName().toString().replaceFirst("\\.java$", "");
                String code = stripComments(Files.readString(file));
                String[] lines = code.split("\n", -1);
                String location = sourceRoot.relativize(file).toString();
                Set<Integer> flagged = new HashSet<>();

                for (Pattern pattern : DIRECT_READS) {
                    Matcher matcher = pattern.matcher(code);
                    while (matcher.find()) {
                        int line = lineOf(code, matcher.start());
                        if (flagged.add(line)) {
                            hits.add(new Hit(className, location + ":" + line, matcher.group().trim(),
                                    collapse(lines[line - 1])));
                        }
                    }
                }

                Matcher fill = FILL_CALL.matcher(code);
                while (fill.find()) {
                    int open = code.indexOf('(', fill.start());
                    String last = lastArgument(code, open);
                    if (last == null || !last.matches("[A-Za-z_]\\w*")) {
                        continue;
                    }
                    int line = lineOf(code, fill.start());
                    if ("ByteArrayOutputStream".equals(nearestDeclaredType(lines, line, last)) && flagged.add(line)) {
                        hits.add(new Hit(className, location + ":" + line,
                                "." + fill.group(1) + "(..," + last + ")", collapse(lines[line - 1])));
                    }
                }
            }
        }
        return hits;
    }

    /** The last top-level argument of the call whose {@code (} is at {@code open}, or {@code null} if unbalanced. */
    private static String lastArgument(String code, int open) {
        int depth = 0;
        int argStart = open + 1;
        List<Integer> commas = new ArrayList<>();
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0 && c == ')') {
                    int from = commas.isEmpty() ? argStart : commas.getLast() + 1;
                    return code.substring(from, i).trim();
                }
            } else if (c == ',' && depth == 1) {
                commas.add(i);
            }
        }
        return null;
    }

    /** The declared type of {@code var} at its nearest declaration at or above {@code line} (1-based), or {@code null}.
     *  Recognises array/generic types ({@code byte[] buffer}) so a bounded chunked {@code in.read(byte[])} is not
     *  mistaken for a heap-buffer fill. */
    private static String nearestDeclaredType(String[] lines, int line, String var) {
        Pattern decl = Pattern.compile("([A-Za-z_][\\w.\\[\\]<>]*)\\s+" + Pattern.quote(var) + "\\s*[=;]");
        for (int i = line - 1; i >= 0; i--) {
            Matcher m = decl.matcher(lines[i]);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String collapse(String line) {
        return line.replaceAll("\\s+", " ").trim();
    }

    /** Blanks out {@code //} and block comments (preserving newlines and string/char literals) so the matcher never
     *  trips on the word "readAllBytes"/"toByteArray" in prose. */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int state = 0; // 0 code, 1 string, 2 char, 3 line-comment, 4 block-comment
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            switch (state) {
                case 0 -> {
                    if (c == '"') { out.append(c); state = 1; }
                    else if (c == '\'') { out.append(c); state = 2; }
                    else if (c == '/' && next == '/') { out.append("  "); i++; state = 3; }
                    else if (c == '/' && next == '*') { out.append("  "); i++; state = 4; }
                    else { out.append(c); }
                }
                case 1 -> {
                    out.append(c);
                    if (c == '\\' && i + 1 < n) { out.append(text.charAt(++i)); }
                    else if (c == '"') { state = 0; }
                }
                case 2 -> {
                    out.append(c);
                    if (c == '\\' && i + 1 < n) { out.append(text.charAt(++i)); }
                    else if (c == '\'') { state = 0; }
                }
                case 3 -> {
                    if (c == '\n') { out.append('\n'); state = 0; } else { out.append(' '); }
                }
                case 4 -> {
                    if (c == '*' && next == '/') { out.append("  "); i++; state = 0; }
                    else { out.append(c == '\n' ? '\n' : ' '); }
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    /** The module sources directory ({@code <repo>/source}) - located by walking up from the working directory to the
     *  first ancestor holding {@code source/} beside {@code build/jenesis}. Fails loudly if the tree is not reachable, so
     *  this structural check never passes vacuously. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("source")) && Files.isDirectory(dir.resolve("build/jenesis"))) {
                return dir.resolve("source");
            }
        }
        throw new AssertionError("could not locate the free repo root (an ancestor holding source/ beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the module sources");
    }
}
