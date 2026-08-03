package build.jenesis.repository.test;

import module java.base;

import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Free-core structural guard (the servable-name enumeration seam EPIC, phase&nbsp;P-F4): every surface that
 * materialises published <em>names</em> - browse children, version folders, OCI catalog/tags, the {@code publish/}
 * asset walk - must route its disclosure decision through the one {@link build.jenesis.repository.store.ServableNames}
 * seam, so a withheld/held artifact's existence cannot leak through a name-enumeration listing. This is a
 * source-scanning guard in the exact mould of its sibling {@code *PrincipleTest}s
 * ({@link FormatScreeningMonopolyPrincipleTest}, {@link ImmutabilityPrincipleTest}, {@link ConfigPrincipleTest}): it
 * reads the sources rather than booting anything, strips comments, classifies each file by token match, and fails the
 * build naming any offender, with a justified allowlist for the genuine non-name-disclosure enumerations.
 *
 * <h2>The scanned set (files that can materialise served names)</h2>
 * <ul>
 *   <li>everything under {@code source/format/} (the ecosystem layout writers whose index/metadata surfaces list
 *       versions/tags: maven-metadata, OCI catalog/tags, the raw directory listing);</li>
 *   <li>everything under {@code source/ui/} (the console browse tree and its namespace quick-links);</li>
 *   <li>{@code source/server/}{@code **}{@code /*Controller.java} (the REST controllers - the server ingress that can
 *       enumerate);</li>
 *   <li>everything under {@code source/walk/} (the {@code publish/}-tree walkers that feed index rebuild);</li>
 *   <li>the {@code PublishedAssets} walker under {@code source/store/} (the one shared {@code publish/} walk behind the
 *       console {@code /assets} export and the server {@code /api/assets} catalogue).</li>
 * </ul>
 *
 * <h2>The classification (per file, over the comment-stripped source)</h2>
 * <ol>
 *   <li><b>enumerating</b> - the file contains any {@link #ENUMERATION_TOKENS raw-enumeration token} that walks stored
 *       names ({@code store.list(} / {@code store.page(}, and the coordinate/version/children/releases enumeration
 *       idioms). On the current free tree only {@code store.list(} and {@code store.page(} fire; the remaining tokens
 *       ({@code .children(}, {@code .coordinates(}, {@code .versions(}, {@code releases(}, {@code blobs.list(},
 *       {@code blobs.page(}) are the enterprise-shaped coordinate/version-enumeration idioms (search, inventory) that
 *       do not yet exist in the free tree - they are live forward guards so a <em>new</em> free surface built in that
 *       shape is caught the moment it lands, not invented matches.</li>
 *   <li><b>screened</b> - the file contains any {@link #SEAM_TOKENS seam token}, i.e. it routes a disclosure decision
 *       through {@code ServableNames} (or the promoted {@code Withheld} marker convention).</li>
 *   <li><b>offender</b> = enumerating &and; &not;screened &and; &not;allowlisted. An offender fails the build.</li>
 * </ol>
 *
 * <h2>The allowlist</h2>
 * A {@code Map<source-relative-path, justification>} of genuine <em>non</em>-name-disclosure enumerations - a walk
 * internal that delivers every key to a consumer which itself screens, retention/GC scans that must see withheld
 * artifacts, etc. On the current free scanned set exactly one entry is needed:
 * {@code walk/store/.../StoreArtifactWalk.java}, the reference layout-neutral store DFS - it pages every key of every
 * namespace ({@code blobs/}, {@code walks/}, {@code publish/}, ...) and hands them to walk consumers; the consumer
 * ({@code RebuildPass}) is where the screen lives ({@code names.state(path) == WITHHELD} skips), so the walker itself
 * discloses no served name and must not screen. Each entry carries a one-line justification, and
 * {@link #the_allowlist_stays_live_and_would_be_an_offender()} fails if an entry's file is gone or has since started
 * screening (so a grant cannot rot into a dead mask).
 *
 * <h2>Non-vacuity &amp; the negative control</h2>
 * The scan asserts it saw {@literal >} 0 enumerating files, that the two known free name-disclosure surfaces
 * ({@code BrowseController}, {@code MavenMetadata}) are among the scanned set <em>and</em> classified enumerating, and
 * that {@literal >=} 1 scanned file references the seam (proving the seam-token list is alive, not a dead matcher that
 * would pass every offender). The <b>negative control was verified during implementation</b>: temporarily deleting the
 * {@code ServableNames} import + call from {@code BrowseController} (unscreening a real free surface) made this test
 * FAIL and name {@code ui/.../BrowseController.java} as an offender; adding a dummy enumerating-without-seam file under
 * {@code source/format/} likewise failed and named it; both were reverted, confirming the guard bites.
 *
 * <p>Like every token-scanning ratchet this is heuristic (helper indirection could hide a raw call); the allowlist,
 * the non-vacuity asserts and the negative-control discipline are what keep it honest, and the seam being the
 * convenient path (a screened listing helper) means indirection buys nothing.
 */
class EnumerationScreenPrincipleTest {

    /**
     * Raw enumeration idioms that walk stored names; any hit marks a file "enumerating". {@code store.list(} /
     * {@code store.page(} are the free store-walk primitives (both fire on the current tree). {@code .children(},
     * {@code .coordinates(}, {@code .versions(}, {@code releases(}, {@code blobs.list(}, {@code blobs.page(} are the
     * coordinate/version-enumeration idioms of the (enterprise-side) inventory and search facades and the blobs
     * namespace; none exists in the free tree today, so they are forward guards that catch a new free surface built in
     * that shape - not invented matches (the non-vacuity check proves at least the store-walk primitives fire).
     */
    private static final List<String> ENUMERATION_TOKENS = List.of(
            "store.list(", "store.page(", "blobs.list(", "blobs.page(",
            ".children(", ".coordinates(", ".versions(", "releases(");

    /**
     * Seam faces; any hit marks a file "screened". {@code ServableNames} is the seam type; {@code .disclosable} covers
     * {@code disclosable(path, policy)} / {@code disclosableKey(...)}; {@code disclosableVersionFolder(} is the
     * maven-metadata version face; {@code withheldHash(} is the bare {@code withheld/<hash>} marker face; {@code
     * screening(} is the streaming listing decorator; {@code reviewSubtree} is the reserved-quarantine-name face; {@code
     * Withheld.is(} is the promoted marker convention. On the current free tree {@code ServableNames}, {@code
     * .disclosable}, {@code disclosableVersionFolder(}, {@code withheldHash(} and {@code reviewSubtree} fire; {@code
     * screening(} and {@code Withheld.is(} are seam faces that adopters may route through as the surfaces evolve.
     */
    private static final List<String> SEAM_TOKENS = List.of(
            "ServableNames", ".disclosable", "withheldHash(", "Withheld.is(",
            "disclosableVersionFolder(", "screening(", "reviewSubtree");

    /**
     * Genuine non-name-disclosure enumerations, keyed by {@code source}-relative path with a one-line justification.
     * An enumerating, unscreened file NOT in this map is an offender that fails the build.
     */
    private static final Map<String, String> ALLOWLIST = allowlist();

    private static Map<String, String> allowlist() {
        Map<String, String> allow = new LinkedHashMap<>();

        // --- The reference store walk: a layout-neutral depth-first descent that pages EVERY key of EVERY namespace
        //     (blobs/, walks/, publish/, ...) through ArtifactStore#page and delivers them to a WalkConsumer. It
        //     discloses no served name itself - the consumer decides. Screening lives in that consumer: RebuildPass
        //     routes each delivered pointer through ServableNames.state and skips WITHHELD. The walker paging all keys
        //     (incl. withheld ones) to a screening consumer is a walk internal, not a name-disclosure surface. ---
        allow.put("walk/store/build/jenesis/repository/walk/store/StoreArtifactWalk.java",
                "layout-neutral store DFS: pages every key of every namespace to walk consumers; the consumer "
                        + "(RebuildPass) screens via ServableNames.state - the walker discloses no served name itself");

        return Map.copyOf(allow);
    }

    @Test
    void every_enumeration_surface_routes_through_the_servable_name_seam() throws IOException {
        Scan scan = scan(sourceRoot());

        // Non-vacuity: a broken scanned-set or matcher that saw nothing would otherwise pass every offender silently.
        assertThat(scan.enumerating())
                .as("the scan found no enumerating files - the scanned-set globs or the enumeration-token list is "
                        + "broken; this structural check would then pass vacuously")
                .isNotEmpty();

        // The two known free name-disclosure surfaces must be present in the scanned set AND classified enumerating -
        // if either drops out of scope or stops enumerating, the guard has lost the surface it exists to protect.
        assertThat(scan.enumerating())
                .as("the free console browse tree (BrowseController) must be scanned and classified enumerating - it "
                        + "lists publish/ children per level")
                .anyMatch(f -> f.endsWith("ui/build/jenesis/repository/ui/BrowseController.java"));
        assertThat(scan.enumerating())
                .as("the maven-metadata version index (MavenMetadata) must be scanned and classified enumerating - it "
                        + "lists version folders under publish/maven/<coord>")
                .anyMatch(f -> f.endsWith("format/maven/build/jenesis/repository/format/maven/MavenMetadata.java"));

        // The seam-token list is alive: at least one scanned file actually references the seam. A dead seam-token list
        // (a rename that matches nothing) would classify every enumerating file "unscreened" - this catches that.
        assertThat(scan.screened())
                .as("no scanned file references the servable-name seam - the SEAM_TOKENS list is dead (a rename?), so "
                        + "the guard would flag every screened surface as an offender")
                .isNotEmpty();

        List<String> offenders = scan.enumerating().stream()
                .filter(f -> !scan.screened().contains(f))
                .filter(f -> !ALLOWLIST.containsKey(f))
                .sorted()
                .map(f -> "  - " + f)
                .toList();

        assertThat(offenders)
                .as("these files enumerate stored names but route no disclosure decision through the servable-name "
                        + "seam (build.jenesis.repository.store.ServableNames), so a withheld/held artifact's existence "
                        + "can leak through the listing. Screen the enumeration - filter names through "
                        + "ServableNames.disclosable / disclosableVersionFolder / withheldHash / the screening(...) "
                        + "decorator under the surface's policy - or, if this enumeration genuinely discloses no served "
                        + "name (a walk internal, a retention/GC scan that must see withheld artifacts), add it to "
                        + "ALLOWLIST (keyed by source-relative path) with a one-line justification.%n%s",
                        String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    void the_allowlist_stays_live_and_would_be_an_offender() throws IOException {
        Scan scan = scan(sourceRoot());
        List<String> dead = ALLOWLIST.keySet().stream()
                .filter(path -> !scan.enumerating().contains(path) || scan.screened().contains(path))
                .sorted()
                .map(path -> "  - " + path)
                .toList();
        assertThat(dead)
                .as("these ALLOWLIST entries are no longer enumerating-and-unscreened - the file was moved/deleted or "
                        + "it now routes through the seam, so the grant masks nothing; remove or update the entry so the "
                        + "allowlist tracks the source and cannot rot into a dead grant.%n%s",
                        String.join(System.lineSeparator(), dead))
                .isEmpty();
    }

    // --- the scan -----------------------------------------------------------------------------------------------------

    /** The classification result: the source-relative path of every scanned file that is enumerating, and of every
     *  scanned file that references the seam. */
    private record Scan(Set<String> enumerating, Set<String> screened) {}

    private static Scan scan(Path sourceRoot) throws IOException {
        Set<String> enumerating = new TreeSet<>();
        Set<String> screened = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(EnumerationScreenPrincipleTest::isJava)::iterator) {
                String relative = sourceRoot.relativize(file).toString().replace(File.separatorChar, '/');
                if (!inScannedSet(relative)) {
                    continue;
                }
                String body = stripComments(Files.readString(file));
                if (ENUMERATION_TOKENS.stream().anyMatch(body::contains)) {
                    enumerating.add(relative);
                }
                if (SEAM_TOKENS.stream().anyMatch(body::contains)) {
                    screened.add(relative);
                }
            }
        }
        return new Scan(enumerating, screened);
    }

    /** The scanned set: files that can materialise served names. {@code source/format/}, {@code source/ui/} and
     *  {@code source/walk/} wholesale; the REST controllers under {@code source/server/}; and the one shared
     *  {@code PublishedAssets} walk under {@code source/store/}. {@code module-info.java} is never a surface. */
    private static boolean inScannedSet(String relative) {
        if (relative.endsWith("/module-info.java") || relative.equals("module-info.java")) {
            return false;
        }
        if (relative.startsWith("format/") || relative.startsWith("ui/") || relative.startsWith("walk/")) {
            return true;
        }
        if (relative.startsWith("server/") && relative.endsWith("Controller.java")) {
            return true;
        }
        return relative.startsWith("store/") && relative.endsWith("/PublishedAssets.java");
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    /** Blanks out {@code //} and {@code /* *}{@code /} comments (preserving newlines and string/char literals) so the
     *  scan never trips on a token that appears inside a javadoc {@code {@code ...}} example rather than in real code.
     *  Ported verbatim from the sibling {@link ImmutabilityPrincipleTest} guard. */
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

    /** The module sources directory ({@code <repo>/source}) - located exactly as the sibling structural tests do, by
     *  walking up from the working directory to the first ancestor holding {@code source/} beside {@code build/jenesis}.
     *  Fails loudly if the tree is not reachable, so this structural check never passes vacuously. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("source")) && Files.isDirectory(dir.resolve("build/jenesis"))) {
                return dir.resolve("source");
            }
        }
        throw new AssertionError("could not locate the free-core repo root (an ancestor holding source/ beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the module sources");
    }
}
