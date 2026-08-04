package build.jenesis.repository.test;

import module java.base;

import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Free-core structural guard for the <b>bounded-listing</b> invariant (CLASS_ELIMINATION_PLAN.md &sect;2.3, Lever&nbsp;B,
 * class C2 &mdash; unbounded work / DoS): every {@code store.list(prefix)} / {@code .list(prefix)} call site in the free
 * {@code source/} tree materialises the immediate children of a store key into heap in one round-trip
 * ({@link build.jenesis.repository.store.ArtifactStore#list} &mdash; "the immediate child names under a key prefix"), so a
 * call site over an <em>attacker-shaped</em> namespace (a per-image tag tree, a hot download probe) is a DoS the way the
 * enterprise {@code UnboundedListingPrincipleTest} guards ENT&nbsp;#203/#210/#211 against &mdash; the fix in every such
 * case is to page the namespace ({@code store.page(prefix, after, limit, consumer)}) rather than {@code list()} it whole.
 * This ratchet fails the build if a NEW {@code .list(} call site appears in free {@code source/} that is not in a
 * <b>censused, justified, pinned (shrink-only)</b> allowlist, forcing its author to answer the one question no one was
 * made to answer before: <em>why is this namespace bounded?</em>
 *
 * <p>It is the free sibling of {@link StreamingPrincipleTest} / {@link EnumerationScreenPrincipleTest} (and the port of
 * the enterprise {@code UnboundedListingPrincipleTest}): a deterministic source scan that reads the sources rather than
 * booting anything, strips comments, tokenises each file, keeps a justified allowlist, an allowlist-liveness hygiene test,
 * a non-vacuity assert, and a documented+performed negative control. The scan covers all of the free {@code source/}
 * (excluding {@code test/}/{@code target/} simply by walking only {@code source/}), scoped exactly as the sibling
 * {@link StreamingPrincipleTest}. Every source file is read <b>charset-tolerantly</b> (UTF-8, falling back to
 * ISO-8859-1) so a file carrying non-UTF-8 bytes can never make the scan throw.
 *
 * <h2>The offender rule</h2>
 * A <b>list call site</b> is any {@code .list(} token whose next character is not {@code )} &mdash; i.e. an
 * argument-bearing {@code .list(prefix)} (a child enumeration), never the argument-less {@code .list()} of a bounded
 * domain collection. Each site is keyed by {@code SimpleClassName#anchor} where the anchor is the {@code list(...)}
 * substring of the call, and is an <b>offender</b> unless the {@link #ALLOWLIST} carries an entry whose class matches and
 * whose anchor is a substring of the call line, with a one-line boundedness justification. The allowlist was seeded from
 * the FULL census at this tip ({@value #ALLOWLIST_SIZE} distinct {@code ClassName#anchor} keys over 24 distinct call
 * lines), each entry read at its call site so the justification is truthful, not assumed.
 *
 * <h2>The boundedness classes</h2>
 * Every surviving free call site falls into one of a few provably-bounded shapes, and the justification names which:
 * <ul>
 *   <li><b>one node's immediate children</b> &mdash; the store contract is "immediate child names", so a
 *       {@code store.list(prefix)} inside an iterative descent (the GC condemned-space walk, the {@code publish/} browse
 *       and asset walks, the alias-hold walk) lists one node's children, the O(depth), never self-recursive walk shape;</li>
 *   <li><b>per-coordinate versions/files</b> &mdash; {@code list("publish/maven/" + coord)} enumerates ONE coordinate's
 *       version folders, and {@code list("oci/uploads/" + id)} ONE upload session's chunks, bounded by that
 *       coordinate/session's authenticated upload count, not by any single unauthenticated request;</li>
 *   <li><b>per-image tags</b> &mdash; {@code list("oci/" + name + "/tags")} lists ONE image's tags (the catalog-inclusion
 *       short-circuit), bounded by that image's publish count; the OCI catalog itself PAGES ({@code store.page}, the
 *       {@code catalogPage} seek-resume primitive), never a whole {@code list};</li>
 *   <li><b>in-flight / pending sets</b> &mdash; {@code list("oci/upload-sessions")} (concurrent chunked pushes),
 *       {@code list(dirtyPrefix)} (pending dirty-index markers, the O(&Delta;) feed read);</li>
 *   <li><b>config / tenant / auth / GC-bookkeeping sets</b> &mdash; {@code list("auth/" + tenant)} (one tenant's auth
 *       entries), {@code list("gc")} (the pass-generation bookkeeping space), the demo-seed emptiness gate;</li>
 *   <li><b>store wrapper / testkit pass-throughs</b> &mdash; the read-only / quota / fault-injecting {@code ArtifactStore}
 *       decorators forward {@code list} to a delegate whose (allowlisted) callers carry the boundedness, and the
 *       {@code FilesystemArtifactStore} backing primitive lists one on-disk directory's entries.</li>
 * </ul>
 * The genuinely attacker-shaped per-request enumerations that were the historical C2 defects are NOT here: the OCI
 * catalog pages ({@code store.page} / {@code catalogPage}), the store DFS ({@code StoreArtifactWalk} + {@code Trees})
 * pages every namespace a bounded round-trip at a time, and {@code ServableNames.disclosableVersionFolder} probes a
 * version's leaves under an explicit {@code PROBE_CAP} that fails CLOSED past the bound. A surfaced unbounded HOT-PATH
 * {@code list()} that should page is a real finding &mdash; it is FIXED (paged), never allowlisted.
 *
 * <h2>Non-vacuity &amp; the negative control</h2>
 * {@link #the_list_matcher_is_alive_and_pins_known_sites} asserts the matcher finds the real census (a dead matcher would
 * pass every leg vacuously), that class names are unambiguous, and pins two known sites. {@link
 * #the_allowlist_size_is_pinned_shrink_only} pins the count at {@value #ALLOWLIST_SIZE}: it may only <em>shrink</em>
 * (delete a call site, delete its entry, decrement the pin); a NEW site can never be masked by ADDING an entry &mdash; it
 * must be paged. {@link #the_allowlist_stays_live} fails if any entry's class/anchor no longer matches a real call line,
 * so a grant cannot rot into a dead mask. The <b>negative control was verified during implementation</b> and is re-run by
 * {@link #negative_control_an_unjustified_list_call_trips}: planting an unjustified {@code store.list("attacker/tree")}
 * string in a real free source file made the offender leg FAIL and name it; removing it restored GREEN &mdash; so the
 * guard bites.
 *
 * <p>Like every token-scanning ratchet this is heuristic (&sect;6 caveat): a namespace enumerated through helper
 * indirection or reflection (a method that {@code list()}s and returns, called elsewhere) evades the token match, and the
 * anchor is a coarse {@code contains} substring, so a brand-new call line that happens to contain an existing anchor is
 * masked. The censused allowlist, the size pin, the liveness leg and the negative control are what keep it honest; it is
 * a ratchet against the idioms this codebase writes, not a proof.
 */
class UnboundedListingPrincipleTest {

    /** The pinned size of {@link #ALLOWLIST}: the distinct {@code ClassName#anchor} keys at this tip. Shrink-only. */
    private static final int ALLOWLIST_SIZE = 23;

    /** A conservative floor on the number of distinct list call lines the matcher must find, so a broken matcher (or a
     *  source walk that finds nothing) cannot pass the offender leg vacuously. The census has 24 today. */
    private static final int LIST_SITE_FLOOR = 20;

    /** Every censused {@code .list(prefix)} call site in free {@code source/}, keyed {@code SimpleClassName#list(anchor)},
     *  with a one-line boundedness justification READ at the call site. An argument-bearing {@code .list(} site whose
     *  class+anchor is not in this map is an offender that fails the build. Seeded from the full census at this tip;
     *  pinned shrink-only. */
    private static final Map<String, String> ALLOWLIST = allowlist();

    private static Map<String, String> allowlist() {
        Map<String, String> a = new LinkedHashMap<>();

        // --- one node's immediate children in an iterative walk: the store contract is "immediate child names", so each
        //     of these lists ONE node's children (O(children), never the whole subtree) on a walk/GC/browse path --------
        a.put("PublishedAssets#list(relative.isEmpty() ? ROOT : ROOT + \"/\" + relative)",
                "one node's immediate children in the published-asset publish/ walk");
        a.put("Publication#list(prefix)", "one node's immediate children in the alias-hold publish-tree walk");
        a.put("MarkSweepGarbageCollector#list(prefix)",
                "one node's immediate children in the GC condemned-subtree drop / reference-batch walk");
        a.put("StoreInvariants#list(prefix)", "one node's immediate children in the testkit store-invariant walk");

        // --- per-coordinate versions / per-session chunks: ONE coordinate's or session's uploads, bounded by its
        //     authenticated publish/upload count (a publisher paying for their own breadth) ------------------------------
        a.put("MavenMetadata#list(\"publish/maven/\" + coordinatePath)",
                "version folders of ONE maven coordinate - bounded by that coordinate's upload count");
        a.put("OciFormat#list(\"oci/uploads/\" + id)",
                "chunk objects of ONE chunked-upload session (concatenated in order / cleaned up) - bounded by that "
                        + "authenticated upload's chunk count");

        // --- per-image tags: the catalog-inclusion short-circuit over ONE image; the catalog itself PAGES --------------
        a.put("OciFormat#list(\"oci/\" + name + \"/tags\")",
                "tags of ONE OCI image (hasSurvivingTag short-circuits at the first servable tag) - bounded by that "
                        + "image's tag count; the catalog itself pages via store.page (catalogPage), never a whole list");

        // --- in-flight / pending sets: bounded by concurrent operations or the pending delta -----------------------------
        a.put("OciFormat#list(\"oci/upload-sessions\")",
                "in-flight OCI chunked-upload session markers (the TTL reaper) - bounded by concurrent uploads");
        a.put("DirtyIndexFeed#list(dirtyPrefix)",
                "the dirty/ index markers - the O(delta) feed read/compaction, bounded by pending changes not index size");

        // --- config / tenant / auth / GC-bookkeeping sets: bounded by operator config or the collector's own structure --
        a.put("Authorization#list(\"auth/\" + tenant)",
                "auth entries of ONE tenant - operator-provisioned (users/roles), bounded by that tenant's config");
        a.put("NodeConsistency#list(PREFIX)", "cluster node fingerprints - bounded by the deployment's node count");
        a.put("MarkSweepGarbageCollector#list(\"gc\")",
                "the gc/ pass-generation bookkeeping space (lastCompletedGeneration / converge) - bounded by the retained "
                        + "pass generations, not artifacts");
        a.put("BrowsePanel#list(\"publish\")",
                "top-level publish/ children for the console namespace panel - one node's immediate children");
        a.put("DemoSeeder#list(\"blobs\")",
                "demo-seed emptiness gate: probes for any content-addressed blob before seeding a demo - runs only on an "
                        + "operator/dev demo-seed action, refuses unless empty, never an attacker-reachable serve path");
        a.put("DemoSeeder#list(\"publish\")",
                "demo-seed emptiness gate: probes for any publish pointer before seeding a demo - same demo-only gate, "
                        + "never a serve path");

        // --- ServableNames disclosure screen: an explicit PROBE_CAP-bounded probe that fails CLOSED past the bound ------
        a.put("ServableNames#list(\"publish/quarantine\" + folder)",
                "review-pointer probe: any child under publish/quarantine<folder> means the version is held - one node's "
                        + "immediate children");
        a.put("ServableNames#list(\"publish\" + folder)",
                "the version's leaves, probed for a withheld leaf under an explicit PROBE_CAP that fails CLOSED past the "
                        + "bound (a folder wider than the cap is screened, never exhaustively listed)");

        // --- store wrapper / testkit pass-throughs: the primitive; boundedness is the (allowlisted) caller's -----------
        a.put("ReadOnlyArtifactStore#list(prefix)", "the read-only ArtifactStore wrapper's list pass-through to its delegate");
        a.put("QuotaArtifactStore#list(prefix)", "the quota-metering ArtifactStore wrapper's list pass-through to its delegate");
        a.put("FaultInjectingStore#delegate.list(prefix)",
                "testkit fault-injecting ArtifactStore wrapper's list pass-through to its delegate");
        a.put("FaultInjectingStore#scoped.list(prefix)",
                "testkit fault-injecting ArtifactStore wrapper's list pass-through to its tenant-scoped delegate");
        a.put("FilesystemArtifactStore#Files.list(dir)",
                "the filesystem backing primitive: lists ONE on-disk directory's immediate entries (the store.list impl)");
        a.put("StoreInvariants#list(\"blobs\")",
                "testkit invariant checker: enumerates stored blobs to assert store invariants in tests - not a serve path");

        return Map.copyOf(a);
    }

    // --- non-vacuity pins --------------------------------------------------------------------------------------------

    private static final String MAVEN = "format/maven/build/jenesis/repository/format/maven/MavenMetadata.java";
    private static final String OCI = "format/oci/build/jenesis/repository/format/oci/OciFormat.java";

    @Test
    void every_list_call_site_is_censused_and_justified() throws IOException {
        Scan scan = scan(sourceRoot());

        // Non-vacuity: the matcher must find the real census, or the offender leg passes silently.
        assertThat(distinctLines(scan.sites()))
                .as("the .list( matcher found fewer than %d distinct call lines - the matcher or the source walk is "
                        + "broken; the offender leg would then pass vacuously", LIST_SITE_FLOOR)
                .isGreaterThanOrEqualTo(LIST_SITE_FLOOR);

        List<String> offenders = offenders(scan.sites(), ALLOWLIST).stream()
                .map(site -> "  - " + site.relPath() + ": " + site.line())
                .distinct()
                .sorted()
                .toList();

        assertThat(offenders)
                .as("these .list(prefix) call sites materialise a store namespace into heap in one round-trip but carry "
                        + "no boundedness justification, so a namespace an attacker can inflate (a per-image tag tree, a "
                        + "hot download probe) is an unpaged DoS - the enterprise ENT #203/#210/#211 class. PAGE it "
                        + "(store.page(prefix, after, limit, consumer), the catalogPage / Trees.descend idiom); or, if the "
                        + "namespace is provably bounded (one node's immediate children in an iterative walk, one "
                        + "coordinate's uploads, a config/tenant/auth/GC-bookkeeping set, a store-wrapper pass-through), "
                        + "add a ClassName#list(anchor) entry to ALLOWLIST with a one-line justification and bump "
                        + "ALLOWLIST_SIZE.%n%s", String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    void the_list_matcher_is_alive_and_pins_known_sites() throws IOException {
        Scan scan = scan(sourceRoot());
        assertThat(scan.sites()).as("the .list( matcher found no call sites at all - the matcher or the source walk is broken").isNotEmpty();

        // Class names must be unique among files that carry a list site, or the ClassName#anchor keying is ambiguous.
        Map<String, Set<String>> byClass = new TreeMap<>();
        for (ListSite site : scan.sites()) {
            byClass.computeIfAbsent(site.className(), k -> new TreeSet<>()).add(site.relPath());
        }
        List<String> ambiguous = byClass.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "  - " + e.getKey() + " -> " + e.getValue())
                .toList();
        assertThat(ambiguous)
                .as("these simple class names carry a list site in more than one file, so a ClassName#anchor allowlist "
                        + "key is ambiguous - key by a more specific name.%n%s", String.join(System.lineSeparator(), ambiguous))
                .isEmpty();

        // Two known sites must be found, so a matcher that silently stops matching cannot pass.
        assertThat(scan.sites())
                .as("MavenMetadata.list(\"publish/maven/\" + coordinatePath) (a per-coordinate version enumeration) must be a scanned list site")
                .anyMatch(s -> s.relPath().equals(MAVEN) && s.line().contains("list(\"publish/maven/\" + coordinatePath)"));
        assertThat(scan.sites())
                .as("OciFormat.list(\"oci/\" + name + \"/tags\") (the per-image tag enumeration) must be a scanned list site")
                .anyMatch(s -> s.relPath().equals(OCI) && s.line().contains("list(\"oci/\" + name + \"/tags\")"));
    }

    @Test
    void the_allowlist_size_is_pinned_shrink_only() {
        assertThat(ALLOWLIST)
                .as("the listing allowlist is pinned at %d entries, shrink-only: deleting a call site deletes its entry "
                        + "and decrements the pin; a NEW list site is never masked by ADDING an entry - it must be paged",
                        ALLOWLIST_SIZE)
                .hasSize(ALLOWLIST_SIZE);
    }

    @Test
    void the_allowlist_stays_live() throws IOException {
        Scan scan = scan(sourceRoot());
        List<String> dead = new ArrayList<>();
        for (String key : ALLOWLIST.keySet()) {
            int hash = key.indexOf('#');
            String className = key.substring(0, hash);
            String anchor = key.substring(hash + 1);
            boolean live = scan.sites().stream()
                    .anyMatch(s -> s.className().equals(className) && s.line().contains(anchor));
            if (!live) {
                dead.add("  - " + key);
            }
        }
        Collections.sort(dead);
        assertThat(dead)
                .as("these allowlist entries no longer match a real list call site (the file was moved/deleted, the call "
                        + "was paged, or the anchor drifted), so the grant masks nothing; remove or update the entry so "
                        + "the allowlist tracks the source and cannot rot into a dead grant.%n%s",
                        String.join(System.lineSeparator(), dead))
                .isEmpty();
    }

    /**
     * Negative control, run live so the guard is proven to bite rather than only documented: a synthetic call site in an
     * un-allowlisted class must be flagged an offender, and allowlisting it must clear the offender list. This is the
     * runnable form of the planted-offender check performed by hand during implementation - planting a
     * {@code store.list("attacker/tree")} string in a real free source file made {@link #every_list_call_site_is_censused_and_justified}
     * FAIL and name it; removing it restored GREEN.
     */
    @Test
    void negative_control_an_unjustified_list_call_trips() {
        ListSite planted = new ListSite("gc/store/build/jenesis/repository/gc/store/NegativeControlProbe.java",
                "NegativeControlProbe", "for (String x : store.list(\"attacker/tree\")) {");
        List<ListSite> sites = List.of(planted);

        assertThat(offenders(sites, ALLOWLIST))
                .as("an unjustified store.list( call must trip the offender leg")
                .containsExactly(planted);

        Map<String, String> withGrant = new LinkedHashMap<>(ALLOWLIST);
        withGrant.put("NegativeControlProbe#list(\"attacker/tree\")", "synthetic - negative control only");
        assertThat(offenders(sites, withGrant))
                .as("once justified, the same call site is clean")
                .isEmpty();
    }

    // --- the scan ----------------------------------------------------------------------------------------------------

    /** One argument-bearing {@code .list(} call site: the source-relative path of its file, the simple class name, and the
     *  trimmed call line the anchor matches against. */
    private record ListSite(String relPath, String className, String line) {}

    private record Scan(List<ListSite> sites) {}

    /** Offenders: every list site with no allowlist entry whose class matches and whose anchor is a substring of the line. */
    private static List<ListSite> offenders(List<ListSite> sites, Map<String, String> allowlist) {
        return sites.stream().filter(site -> !allowed(site, allowlist)).toList();
    }

    private static boolean allowed(ListSite site, Map<String, String> allowlist) {
        String prefix = site.className() + "#";
        return allowlist.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .anyMatch(key -> site.line().contains(key.substring(prefix.length())));
    }

    private static long distinctLines(List<ListSite> sites) {
        return sites.stream().map(s -> s.className() + '\0' + s.line()).distinct().count();
    }

    private static Scan scan(Path sourceRoot) throws IOException {
        List<ListSite> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(UnboundedListingPrincipleTest::isJava)::iterator) {
                String relative = sourceRoot.relativize(file).toString().replace(File.separatorChar, '/');
                String className = file.getFileName().toString();
                className = className.substring(0, className.length() - ".java".length());
                String body = stripComments(readTolerant(file));
                for (String rawLine : body.split("\n", -1)) {
                    int idx = 0;
                    while ((idx = rawLine.indexOf(".list(", idx)) >= 0) {
                        int after = idx + ".list(".length();
                        if (after < rawLine.length() && rawLine.charAt(after) != ')') {
                            sites.add(new ListSite(relative, className, rawLine.trim()));
                        }
                        idx = after;
                    }
                }
            }
        }
        return new Scan(List.copyOf(sites));
    }

    /** Read a source file charset-tolerantly: UTF-8 first, falling back to ISO-8859-1 when the file carries non-UTF-8
     *  bytes (which trip {@code grep} as binary), so the scan never throws on it. */
    private static String readTolerant(Path file) throws IOException {
        try {
            return Files.readString(file);
        } catch (CharacterCodingException malformed) {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        }
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    /** Blanks out {@code //} and {@code /* *}{@code /} comments (preserving newlines and string/char literals) so the scan
     *  never trips on a {@code .list(} that appears inside a javadoc example rather than in real code. Ported verbatim from
     *  the sibling {@link StreamingPrincipleTest} / {@link EnumerationScreenPrincipleTest} guards. */
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
     *  walking up from the working directory to the first ancestor holding {@code source/} beside {@code build/jenesis}. */
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
