package build.jenesis.repository.test;

import module java.base;

import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Free-core structural guard (the withhold-egress/materialization invariant EPIC, Audit-23, phase&nbsp;P3): the
 * withhold-change feed cannot be silently unhooked. A retroactive hold reaches a durable, name-bearing derived artifact
 * (the enterprise published index) only if every writer of the two durable withhold conventions fires an after-commit
 * signal by construction - so this pins the free-core choke points that fire it, in the exact mould of its sibling
 * {@code *PrincipleTest}s ({@link EnumerationScreenPrincipleTest}, {@link FormatScreeningMonopolyPrincipleTest},
 * {@link ImmutabilityPrincipleTest}): it reads the sources rather than booting anything, strips comments, and fails the
 * build if a choke point has lost its notify or if a raw {@code "withheld/} marker literal has reappeared outside the
 * one class that owns the convention.
 *
 * <h2>The three legs</h2>
 * <ol>
 *   <li><b>Marker face.</b> {@code store/spi/.../Withheld.java} must fire the feed in BOTH transition primitives - its
 *       {@code mark} body contains the transition-ON notify ({@code Publication.notifyWithheld}) and its {@code clear}
 *       body the transition-OFF notify ({@code Publication.notifyWithholdCleared}). A refactor that drops either leg
 *       (marking without signalling) fails here.</li>
 *   <li><b>Pointer face.</b> {@code store/spi/.../Publication.java}'s {@code link} body contains the quarantine-gate
 *       notify branch (both the {@code isQuarantinePath(} boundary-helper guard and {@code notifyWithheld(}), and its
 *       {@code unpublish} bodies (both the string and the descriptor variant) that same gate plus the cleared-notify
 *       ({@code notifyWithholdCleared(}). So a new hold writer routes through {@code link}/{@code unpublish} and fires
 *       the feed for free, and unhooking the branch fails the build. (The gate was the raw {@code "/quarantine"} literal
 *       before it was hardened into the exact-boundary {@code isQuarantinePath} helper; the scan tracks the helper.)</li>
 *   <li><b>Raw-marker hygiene.</b> Any source file whose comment-stripped body still contains the raw {@code "withheld/}
 *       string literal - other than {@code Withheld.java}, which owns the {@code ROOT} constant - is an offender. This is
 *       what makes "every marker write fires the feed" structural rather than aspirational: a raw {@code store.write(
 *       "withheld/"+hex,...)} bypasses {@code Withheld.mark} and its notify. After P3 migrates {@code OciManifests} and
 *       {@code OciFormat}, the {@link #RAW_MARKER_ALLOWLIST allowlist} is EMPTY - a raw literal reappearing must be
 *       migrated to {@code Withheld.mark}/{@code Withheld.is} (or {@code ServableNames.withheldHash}), not masked.</li>
 * </ol>
 *
 * <h2>Non-vacuity &amp; the negative control</h2>
 * The scan asserts it located and read {@code Withheld.java} and {@code Publication.java} (so a broken source root can
 * never pass it vacuously) and that the notify tokens are actually present. The <b>negative control was verified during
 * implementation</b>: temporarily deleting the {@code notifyWithheld} call from {@code Withheld.mark} made leg&nbsp;1
 * FAIL naming that method; reverting a single {@code OciFormat} probe to a raw {@code store.exists("withheld/"+hex)}
 * made leg&nbsp;3 FAIL naming {@code OciFormat.java}; both were reverted, confirming the guard bites. Like every
 * token-scanning ratchet this is heuristic (helper indirection could hide a call); the non-vacuity asserts, the
 * empty-and-live allowlist and the negative-control discipline are what keep it honest.
 */
class WithholdFeedPrincipleTest {

    /** The transition-ON / transition-OFF notify tokens the feed fires through. */
    private static final String WITHHELD_NOTIFY = "notifyWithheld";
    private static final String CLEARED_NOTIFY = "notifyWithholdCleared";

    /** The quarantine-convention gate token the pointer face's choke points must carry: the {@code /quarantine}
     *  boundary literal was refactored into a {@code Publication.isQuarantinePath(...)} helper (an exact {@code /}-subtree
     *  match, not a bare {@code startsWith("/quarantine")}), so the choke-point bodies now name the helper rather than the
     *  raw literal. Tracking the helper token keeps the ratchet's intent - link/unpublish gate on the quarantine
     *  convention - without re-pinning the moved literal. */
    private static final String QUARANTINE_GATE = "isQuarantinePath(";

    /** The raw marker-literal that must not appear outside {@code Withheld.java} - a write of it bypasses the feed. */
    private static final String RAW_MARKER = "\"withheld/";

    /** The one file allowed to hold the raw {@code "withheld/} literal: it owns the {@code ROOT} constant of the
     *  convention and is the single class that reads/writes the marker through {@code Withheld.mark/clear/is}. */
    private static final String MARKER_OWNER = "store/spi/build/jenesis/repository/store/Withheld.java";

    /**
     * Files (other than {@link #MARKER_OWNER}) permitted to carry a raw {@code "withheld/} literal, keyed by
     * {@code source}-relative path with a one-line justification. EMPTY after P3 migrated the OCI sites; a new raw
     * literal must be migrated to the {@code Withheld}/{@code ServableNames} faces, not allowlisted, unless it is a
     * genuine non-marker use (none exists today).
     */
    private static final Map<String, String> RAW_MARKER_ALLOWLIST = Map.of();

    @Test
    void the_marker_face_fires_the_feed_in_both_mark_and_clear() throws IOException {
        Path source = sourceRoot();
        String withheld = stripComments(Files.readString(source.resolve(MARKER_OWNER)));

        String markBody = methodBody(withheld, "void mark(");
        String clearBody = methodBody(withheld, "void clear(");

        assertThat(markBody)
                .as("Withheld.mark must fire the transition-ON withhold-change signal after the durable write - a mark "
                        + "that does not signal cannot retract a durable index consumer")
                .contains(WITHHELD_NOTIFY);
        assertThat(clearBody)
                .as("Withheld.clear must fire the transition-OFF signal after the durable delete")
                .contains(CLEARED_NOTIFY);
    }

    @Test
    void the_pointer_face_fires_the_feed_in_link_and_unpublish() throws IOException {
        Path source = sourceRoot();
        String publication = stripComments(Files.readString(
                source.resolve("store/spi/build/jenesis/repository/store/Publication.java")));

        String linkBody = methodBody(publication, "void link(");
        assertThat(linkBody)
                .as("Publication.link must GATE on the quarantine convention (the %s boundary helper) AND fire the "
                        + "transition-ON signal on a fresh /quarantine pointer - the pointer face of the feed, so every "
                        + "hold writer that links a review pointer signals by construction", QUARANTINE_GATE)
                .contains(QUARANTINE_GATE)
                .contains(WITHHELD_NOTIFY + "(");

        String unpublishString = methodBody(publication, "void unpublish(String requestPath)");
        assertThat(unpublishString)
                .as("Publication.unpublish(String) must gate on the quarantine convention (%s) AND fire the "
                        + "transition-OFF signal when a /quarantine pointer is removed", QUARANTINE_GATE)
                .contains(QUARANTINE_GATE)
                .contains(CLEARED_NOTIFY + "(");

        String unpublishDescriptor = methodBody(publication, "void unpublish(ArtifactDescriptor described)");
        assertThat(unpublishDescriptor)
                .as("Publication.unpublish(ArtifactDescriptor) must gate on the quarantine convention (%s) AND fire the "
                        + "transition-OFF signal likewise", QUARANTINE_GATE)
                .contains(QUARANTINE_GATE)
                .contains(CLEARED_NOTIFY + "(");
    }

    @Test
    void no_file_holds_a_raw_withheld_marker_literal_outside_the_marker_owner() throws IOException {
        Path source = sourceRoot();
        List<String> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files.filter(WithholdFeedPrincipleTest::isJava)::iterator) {
                String relative = source.relativize(file).toString().replace(File.separatorChar, '/');
                scanned.add(relative);
                if (relative.equals(MARKER_OWNER) || RAW_MARKER_ALLOWLIST.containsKey(relative)) {
                    continue;
                }
                if (stripComments(Files.readString(file)).contains(RAW_MARKER)) {
                    offenders.add(relative);
                }
            }
        }

        // Non-vacuity: a broken source walk that read nothing would pass every offender silently.
        assertThat(scanned)
                .as("the scan read no source files - the source root is broken; this check would pass vacuously")
                .isNotEmpty();

        assertThat(offenders.stream().sorted().map(f -> "  - " + f).toList())
                .as("these files write/read a raw \"withheld/ marker literal instead of routing through the Withheld "
                        + "class, so they bypass the withhold-change feed (a raw store.write(\"withheld/\"+hash,...) "
                        + "marks a hold without firing onWithheld, and a durable index consumer never retracts it). "
                        + "Migrate the write to Withheld.mark/clear and the read to Withheld.is / "
                        + "ServableNames.withheldHash; only add to RAW_MARKER_ALLOWLIST for a genuine non-marker use "
                        + "with a one-line justification.%n%s", String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    void the_raw_marker_allowlist_stays_live_and_would_be_an_offender() throws IOException {
        Path source = sourceRoot();
        List<String> dead = new ArrayList<>();
        for (String path : RAW_MARKER_ALLOWLIST.keySet()) {
            Path file = source.resolve(path);
            if (!Files.exists(file) || !stripComments(Files.readString(file)).contains(RAW_MARKER)) {
                dead.add(path);
            }
        }
        assertThat(dead.stream().sorted().map(f -> "  - " + f).toList())
                .as("these RAW_MARKER_ALLOWLIST entries no longer carry a raw \"withheld/ literal (file moved/deleted "
                        + "or migrated) - remove the grant so the allowlist cannot rot into a dead mask.%n%s",
                        String.join(System.lineSeparator(), dead))
                .isEmpty();
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    /** The body of the first method whose signature contains {@code signatureToken}, brace-matched from the method's
     *  opening {@code {} to its close (quote-aware, so a brace inside a string literal never mis-balances it). Fails
     *  loudly if the token or a balanced body is not found, so a rename can never make an assertion pass vacuously. */
    private static String methodBody(String source, String signatureToken) {
        int at = source.indexOf(signatureToken);
        assertThat(at).as("method signature '%s' not found - a rename would make the guard vacuous", signatureToken)
                .isGreaterThanOrEqualTo(0);
        int open = source.indexOf('{', at);
        assertThat(open).as("no method body brace after '%s'", signatureToken).isGreaterThanOrEqualTo(0);
        int depth = 0;
        int state = 0; // 0 code, 1 string, 2 char
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            switch (state) {
                case 0 -> {
                    if (c == '"') {
                        state = 1;
                    } else if (c == '\'') {
                        state = 2;
                    } else if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            return source.substring(open, i + 1);
                        }
                    }
                }
                case 1 -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = 0;
                    }
                }
                case 2 -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '\'') {
                        state = 0;
                    }
                }
                default -> { }
            }
        }
        throw new AssertionError("unbalanced method body for '" + signatureToken + "'");
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    /** Blanks out {@code //} and block comments (preserving newlines and string/char literals) so the scan never trips
     *  on a token inside a javadoc {@code {@code ...}} example. Ported verbatim from the sibling structural guards. */
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

    /** The module sources directory ({@code <repo>/source}), located by walking up from the working directory to the
     *  first ancestor holding {@code source/} beside {@code build/jenesis} - exactly as the sibling structural tests do.
     *  Fails loudly if unreachable, so this check never passes vacuously. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("source")) && Files.isDirectory(dir.resolve("build/jenesis"))) {
                return dir.resolve("source");
            }
        }
        throw new AssertionError("could not locate the free-core repo root (an ancestor holding source/ beside "
                + "build/jenesis) from working directory " + start);
    }
}
