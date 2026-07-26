package build.jenesis.repository.test;

import module java.base;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces engineering principle <b>&sect;11 &mdash; "prefer immutability; scope mutation locally"</b> structurally, at
 * build time, across the <em>free core</em> module sources. Ported from the enterprise
 * {@code ImmutabilityPrincipleTest} (enterprise&nbsp;#112) so the free core is held to the same standard: the enterprise
 * has a structural guard for the mutable-static half of &sect;11, the free core (until now) had none &mdash; an audit
 * AMBER. This is that guard for the free core, mirroring the enterprise matcher and allowlist idiom exactly, only scoped
 * to the free core's own {@code source/} tree.
 *
 * <p>&sect;11 (from {@code PRINCIPLES.md} / {@code AGENTS.md}): <i>types are immutable by default &hellip; there are
 * <b>no mutable static state</b>. Mutability is confined to <b>method-local</b> scope &mdash; a loop accumulator, a
 * {@code StringBuilder}, a builder assembled and then frozen before it escapes. A shared object a reader holds cannot
 * then change under it mid-view (&sect;10), and reading shared state needs no lock.</i>
 *
 * <p>Until now &sect;11 was guarded only by review (the {@code PRINCIPLES.md} pass run by eye over each diff); nothing
 * fails the build when a <em>new</em> mutable {@code static} field is introduced. This test is the structural guard for
 * the mutable-static half: it scans the source tree for {@code static} <em>fields</em> that are not {@code final} and
 * fails the build on any that is not an explicitly justified, safe exception &mdash; the {@code
 * FormatScreeningMonopolyPrincipleTest} mould (a deterministic source scan with a justified allowlist), applied to the
 * immutability principle.
 *
 * <h2>What is flagged (the matcher)</h2>
 * Over the comment-stripped source of every {@code .java} under {@code source/}, a hit is a <b>member field
 * declaration that carries the {@code static} modifier but not {@code final}</b> &mdash; the shape
 * {@code [annotations] [modifiers incl. static, without final] <Type> <name> [= &hellip;] ;}. The matcher is a
 * deterministic line/element regex over the stripped source ({@link #STATIC_FIELD}) with these false-positive guards,
 * so it flags only genuine mutable-static fields:
 * <ul>
 *   <li><b>{@code static final} constants are not flagged</b> &mdash; the modifier run is captured and a hit is kept
 *       only when it contains {@code static} <em>and</em> lacks {@code final}. A {@code private static final Logger},
 *       an interface constant, an {@code enum} value all carry {@code final} (explicitly or implicitly) and pass.</li>
 *   <li><b>static METHODS are not flagged</b> &mdash; the field name must be immediately followed by {@code =} or
 *       {@code ;}; a method's name is followed by {@code (} (its parameter list), so {@code static <T> T foo(&hellip;)}
 *       never matches.</li>
 *   <li><b>static nested CLASS / INTERFACE / ENUM / RECORD declarations are not flagged</b> &mdash; their "name" is
 *       followed by {@code {} / {@code extends} / {@code implements} / {@code (} (a record header), not {@code =}/{@code ;};
 *       and as belt-and-braces the captured "type" token is rejected when it is one of those keywords.</li>
 *   <li><b>{@code static &#123;&hellip;&#125;} initializer blocks are not flagged</b> &mdash; {@code static} is followed
 *       by {@code {}, not a type-and-name, so no field is matched.</li>
 *   <li><b>local variables are not flagged</b> &mdash; {@code static} is illegal on a Java local, so a {@code static}
 *       modifier run only ever appears on a member; the check need not (and does not) reason about method bodies. The
 *       {@code import static} form starts the line with {@code import} (not a modifier), so it is not a field either.</li>
 * </ul>
 *
 * <h2>The allowlist</h2>
 * Every current hit is a <b>safe, justified exception</b> &mdash; not the mutable-shared-state &sect;11 forbids, but a
 * boot-time wiring seam or a memoized/once-set holder of an <em>immutable</em> value, published through {@code volatile}
 * so a reader's lock-free read is safe (exactly the "reading shared state needs no lock" the principle endorses). Each
 * is listed in {@link #ALLOWLIST} keyed by {@code SimpleClassName#fieldName} (stable across unrelated line shifts, never
 * a line number) with a one-line justification derived from reading the actual code. A hit that matches no allowlist
 * entry <b>fails the build</b>, naming the file, line and field, and pointing the author at &sect;11: make it
 * {@code final} (and immutable), confine the mutation to method-local scope, or &mdash; if it is genuinely an
 * unavoidable, safe boot-time/memoized seam &mdash; add an allowlist entry <em>with</em> a justification.
 *
 * <h2>Scope &amp; honest limitations</h2>
 * The scan covers all of {@code source/} (every free-core module, including the server production surface &mdash; read
 * only). It excludes the {@code test/} trees (a test may legitimately hold a mutable static, e.g. a capturing logger or
 * a shared fixture) and any generated {@code target/} output, both simply by walking only {@code source/}. Note the
 * {@code static final List} ServiceLoader holders ({@code Publication.DISCOVERED} / {@code Publication.OBSERVERS}, the
 * {@code MavenFormat.MODULE_VIEWS} view list) carry {@code final} and so are never flagged &mdash; they are
 * immutable-by-construction ({@code List.copyOf} of a once-off discovery pass) and need no allowlist entry here. Like
 * its sibling structural tests it is a text scan matching the idioms above; the known gaps, none of which hides a
 * violation on the current tree: a field whose modifier run or type spills across multiple physical lines in an unusual
 * way, and a multi-declarator field ({@code static int a, b;}) reports only its first declarator. The allowlist is keyed
 * on the <em>simple</em> class name, so two flagged fields of the same name in two classes sharing a simple name would
 * be indistinguishable &mdash; no such collision exists among the flagged fields today. The value it delivers, and the
 * case that matters, is that a new {@code static} field that is not {@code final} is caught the moment it is added,
 * unless the author consciously justifies it as a safe exception.
 */
class ImmutabilityPrincipleTest {

    /**
     * A justified non-final static: a boot-time wiring seam or a memoized / once-set holder of an <em>immutable</em>
     * value, published via {@code volatile} for safe lock-free reads &mdash; never the mutable shared state &sect;11
     * forbids. Keyed on {@code SimpleClassName#fieldName} so the entry survives unrelated line shifts.
     */
    private record Allow(String className, String fieldName, String justification) {}

    private static final List<Allow> ALLOWLIST = allowlist();

    private static List<Allow> allowlist() {
        List<Allow> a = new ArrayList<>();

        // --- Boot-time config-lookup wiring seam: a single volatile reference to the deployment's config lookup
        //     (an immutable UnaryOperator handle - the Spring Environment, or the system-property/env default), swapped
        //     once at boot by the application shell via configure(...) and read lock-free on every feature/selection
        //     query. A wired collaborator, not mutable data churned on a live object; a reader seeing the boot lookup
        //     installed is the intended, safe behaviour. §11 explicitly sanctions such lock-free reads of shared state
        //     (mirrors the enterprise Forwarding#credentials / Webhooks#enabled boot-time seams). ---
        a.add(new Allow("Features", "config",
                "boot-time config-lookup wiring seam: a volatile reference to the deployment's config lookup (an "
                        + "immutable UnaryOperator handle), defaulting to the system-property/env lookup and installed "
                        + "once at boot via configure(UnaryOperator) (reset() restores the default), read lock-free by "
                        + "enabled/selection/active - a wired collaborator, not a churned mutable object; §11 sanctions "
                        + "the lock-free read"));

        return List.copyOf(a);
    }

    /** One flagged non-final static field: the class it lives in, its field name, source location, and the flagged
     *  (comment-stripped, whitespace-collapsed) declaration line. */
    private record Hit(String className, String fieldName, String location, String codeLine) {}

    /** A member field declaration carrying a modifier run: group 1 the modifiers, group 2 the declared type, group 3
     *  the field name. The name is anchored to a following {@code =} or {@code ;} so a method (name followed by
     *  {@code (}) and a nested type (name followed by {@code {}/{@code extends}/{@code implements}/{@code (}) never
     *  match; a {@code static &#123;&#125;} block matches no type-and-name at all. Since {@code static} is illegal on a
     *  local, any {@code static} in the modifier run is necessarily a member. */
    private static final Pattern STATIC_FIELD = Pattern.compile(
            "(?m)^[ \\t]*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*"
                    + "((?:(?:public|protected|private|static|final|volatile|transient)\\b\\s+)+)"
                    + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;{}=]*>)?(?:\\s*\\[\\s*\\])*)\\s+"
                    + "([A-Za-z_$][\\w$]*)\\s*[=;]");

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");

    @Test
    void every_non_final_static_field_is_an_allowlisted_safe_exception() throws IOException {
        Scan scan = scan(sourceRoot());

        assertThat(scan.totalStaticFields())
                .as("the source scan found static fields - the immutability check is not vacuous (a broken matcher "
                        + "that saw nothing would otherwise pass silently)")
                .isPositive();

        List<String> violations = scan.hits().stream()
                .filter(h -> ALLOWLIST.stream().noneMatch(x ->
                        x.className().equals(h.className()) && x.fieldName().equals(h.fieldName())))
                .map(h -> "  - " + h.location() + "  [" + h.className() + "#" + h.fieldName() + "]  " + h.codeLine())
                .sorted()
                .toList();

        assertThat(violations)
                .as("these non-final static fields are mutable static state, which violates the immutability principle "
                        + "(PRINCIPLES.md §11): there must be no mutable static state - a shared object a reader holds "
                        + "cannot change under it mid-view, and reading shared state needs no lock. Make the field "
                        + "'final' and hold an immutable value, or confine the mutation to method-local scope (a loop "
                        + "accumulator, a builder frozen before it escapes). If this is genuinely an unavoidable, safe "
                        + "boot-time wiring seam or a memoized/once-set holder of an immutable value published via "
                        + "volatile, add it to ALLOWLIST (keyed SimpleClassName#fieldName) with a one-line "
                        + "justification.%n%s", String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void the_allowlist_stays_live() throws IOException {
        Scan scan = scan(sourceRoot());
        List<String> dead = ALLOWLIST.stream()
                .filter(x -> scan.hits().stream().noneMatch(h ->
                        x.className().equals(h.className()) && x.fieldName().equals(h.fieldName())))
                .map(x -> "  - " + x.className() + "#" + x.fieldName())
                .sorted()
                .toList();
        assertThat(dead)
                .as("these ALLOWLIST entries no longer match any flagged non-final static field - the code changed "
                        + "(the field was removed or made final); remove or update the entry so the allowlist tracks "
                        + "the source and cannot silently mask a future violation")
                .isEmpty();
    }

    // --- the scan -------------------------------------------------------------------------------------------------

    /** The scan result: every flagged non-final static field, plus the count of ALL static fields seen (final ones
     *  included) so the check can prove it is not matching a vacuous nothing. */
    private record Scan(List<Hit> hits, int totalStaticFields) {}

    private static Scan scan(Path sourceRoot) throws IOException {
        List<Hit> hits = new ArrayList<>();
        int totalStaticFields = 0;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(ImmutabilityPrincipleTest::isJava)::iterator) {
                String className = file.getFileName().toString().replaceFirst("\\.java$", "");
                String code = stripComments(Files.readString(file));
                String[] lines = code.split("\n", -1);
                String location = sourceRoot.relativize(file).toString();

                Matcher matcher = STATIC_FIELD.matcher(code);
                while (matcher.find()) {
                    Set<String> mods = Set.of(matcher.group(1).trim().split("\\s+"));
                    if (!mods.contains("static") || TYPE_KEYWORDS.contains(matcher.group(2))) {
                        continue;
                    }
                    totalStaticFields++;
                    if (mods.contains("final")) {
                        continue;
                    }
                    int line = lineOf(code, matcher.start(1));
                    hits.add(new Hit(className, matcher.group(3), location + ":" + line, collapse(lines[line - 1])));
                }
            }
        }
        return new Scan(hits, totalStaticFields);
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

    /** Blanks out {@code //} and {@code /* *}{@code /} comments (preserving newlines and string/char literals) so the
     *  matcher never trips on the word "static" in prose or a string literal. Ported from the enterprise guard. */
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
