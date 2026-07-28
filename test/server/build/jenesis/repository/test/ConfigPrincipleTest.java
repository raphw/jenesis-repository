package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.SettingsCatalogue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the free core's configuration principle structurally, at build time: runtime configuration reaches the app
 * by exactly two sanctioned paths and nothing else. Ported from the enterprise {@code ConfigPrincipleTest} (the same
 * mould as {@code ImmutabilityPrincipleTest} - a deterministic comment-agnostic source scan with a justified
 * allowlist) so the free core is held to the same standard, scoped to the free core's own {@code source/} tree.
 *
 * <ol>
 *   <li><b>(a) env-var bootstrap</b> - a deploy-time key an operator sets in the environment/file configuration that
 *       binds at startup (the store backend and its credentials, the fixed-tenant routing, the auth / read-only
 *       deployment flags, the per-node consistency enable/identity). These are intentionally NOT runtime dials; they
 *       are the {@link #ENV_VAR_BOOTSTRAP} allowlist, each carrying a one-line justification.</li>
 *   <li><b>(b) declared runtime setting</b> - a runtime-tunable dial declared in the free {@link SettingsCatalogue}
 *       (#146), the free-core analogue of the enterprise {@code SettingsContributor} SPI. Enumerated from the source
 *       (every {@code new Setting("<literal>")} across {@code source/}) and unioned with the runtime
 *       {@link SettingsCatalogue#keys()} catalogue.</li>
 * </ol>
 *
 * <p>A config key the code READS that is neither a declared setting nor an allowlisted bootstrap key is a <b>stranded
 * key</b>: unreachable without hand-editing a store object, so it fails here, naming the key and a read site and
 * pointing the developer at the two sanctioned paths. This is exactly what caught the previously-undeclared
 * {@code jenesis.consistency.{heartbeat,staleness-window,sweep-interval,sweep-intervals,dead-after}} dials (#146),
 * now declared in {@link SettingsCatalogue}. The reverse - a declared setting nobody reads - is a dead dial, not a
 * stranded key, and is out of this check's scope.
 *
 * <h2>How the reads are enumerated</h2>
 * The free core reads effective configuration two ways: directly as {@code config.apply("<key>")} (a
 * {@link java.util.function.UnaryOperator} lookup), and through small per-module reader helpers that take the lookup
 * as their first argument and a literal key as their second - {@code millis(config, "<key>", ...)} in
 * {@code NodeConsistency}/{@code NodeFingerprintPublisher}, {@code integer(config, "<key>", ...)} /
 * {@code duration(config, "<key>")} in the gc / walk providers. This scan captures BOTH idioms - {@code .apply("...")}
 * and {@code (config, "...")} - over every {@code .java} under {@code source/}, which is what makes it see the helper
 * reads the enterprise {@code .apply}-only scan would miss. The single literal-{@code config} false positive from
 * {@code Objects.requireNonNull(config, "config")} is filtered.
 *
 * <p>Both scans match keys written as a <em>string literal</em> - the shape of the overwhelming majority of sites; a
 * key reached only through an indirection (a {@code static final String} constant read as {@code apply(KEY)}) escapes
 * the scan, but none exists on the current tree. A stranded key added the ordinary way - a literal
 * {@code config.apply("new-key")} with no matching setting - IS caught, which is the case that matters.
 */
class ConfigPrincipleTest {

    /**
     * Path (a): keys that are legitimately deploy-time / bootstrap configuration bound from the environment or file
     * config at startup, and intentionally NOT runtime dials. Each entry carries its justification. Anything read but
     * absent from both this list and the declared {@link SettingsCatalogue} is a stranded key.
     */
    private static final Map<String, String> ENV_VAR_BOOTSTRAP = envVarBootstrap();

    private static Map<String, String> envVarBootstrap() {
        Map<String, String> allow = new LinkedHashMap<>();

        // --- Store backend selection + credentials: which store the deployment runs on, and the credentials it
        //     presents to that backend, are deploy-time choices bound from the environment (ArtifactStoreProvider
        //     reads them through config.apply(JENESIS_*)); a credential must never live in a readable store object. ---
        allow.put("JENESIS_STORE_ROOT", "filesystem store root - deploy-time backend selection");
        allow.put("JENESIS_AWS_BUCKET", "S3 store bucket - deploy-time backend selection");
        allow.put("JENESIS_AWS_ENDPOINT", "S3 endpoint - deploy-time backend selection");
        allow.put("JENESIS_AWS_REGION", "S3 region - deploy-time backend selection");
        allow.put("JENESIS_AWS_ALLOW_INSECURE_ENDPOINT", "S3 insecure-endpoint opt-in - deploy-time backend selection");
        allow.put("JENESIS_AWS_ACCESS_KEY_ID", "S3 access key id - deploy-time store-backend credential");
        allow.put("JENESIS_AWS_SECRET_ACCESS_KEY", "S3 secret access key - deploy-time store-backend credential");
        allow.put("JENESIS_AWS_SSE_KMS_KEY_ID", "S3 SSE-KMS key id - deploy-time backend selection");
        allow.put("JENESIS_GCS_BUCKET", "GCS store bucket - deploy-time backend selection");
        allow.put("JENESIS_GCS_ENDPOINT", "GCS endpoint - deploy-time backend selection");
        allow.put("JENESIS_GCS_REGION", "GCS region - deploy-time backend selection");
        allow.put("JENESIS_GCS_ALLOW_INSECURE_ENDPOINT", "GCS insecure-endpoint opt-in - deploy-time backend selection");
        allow.put("JENESIS_GCS_ACCESS_KEY_ID", "GCS access key id - deploy-time store-backend credential");
        allow.put("JENESIS_GCS_SECRET_ACCESS_KEY", "GCS secret access key - deploy-time store-backend credential");
        allow.put("JENESIS_AZURE_CONNECTION_STRING", "Azure store connection string - deploy-time store-backend "
                + "credential");
        allow.put("JENESIS_AZURE_CONTAINER", "Azure store container - deploy-time backend selection");

        // --- Fixed-tenant routing: the single-space routing target read at boot - a restart-level deployment shape,
        //     not a runtime dial. ---
        allow.put("jenesis.repository.tenant", "fixed-tenant routing target - deploy-time deployment shape");

        // --- Deployment-wide auth/read-only flags: the enforcement mode and the read-only gate are deployment shape
        //     bound at startup (like the store backend), not console dials. ---
        allow.put("auth", "whether the deployment enforces credentials - deploy-time deployment shape");
        allow.put("read-only", "whether the deployment refuses writes - deploy-time deployment shape");

        // --- Multi-node consistency (WCON.2): whether this deployment participates in the fingerprint publish/compare,
        //     and this node's stable identity. Both are per-instance deploy-time configuration - the node id is unique
        //     to each process (it cannot be one store-shared setting the whole fleet reads), and the enable toggle is a
        //     deployment shape so an otherwise-clean single-node store writes no fingerprint. ---
        allow.put("jenesis.consistency.enabled", "multi-node consistency publish opt-in (WCON.2) - deploy-time "
                + "deployment shape, per-node");
        allow.put("jenesis.consistency.node-id", "this node's stable consistency identity (WCON.2) - per-instance "
                + "deploy-time value, unique to each process, so it cannot be a fleet-shared store setting");

        return Map.copyOf(allow);
    }

    @Test
    void every_config_key_read_is_a_declared_setting_or_an_env_var_bootstrap_key() throws IOException {
        Map<String, String> reads = readConfigKeys(sourceRoot());
        assertThat(reads).as("the source scan found config reads - the check is not vacuous").isNotEmpty();

        Set<String> declared = declaredKeys(sourceRoot());
        assertThat(declared).as("the free SettingsCatalogue declares runtime settings - the declared set is not empty")
                .isNotEmpty();

        List<String> stranded = reads.keySet().stream()
                .filter(key -> !declared.contains(key))
                .filter(key -> !ENV_VAR_BOOTSTRAP.containsKey(key))
                .sorted()
                .toList();

        assertThat(stranded)
                .as("stranded config keys - each is read at the listed site but reaches the app through NEITHER "
                        + "sanctioned path, so it is unreachable without hand-editing a store object. Give it a home: "
                        + "declare it in SettingsCatalogue (a runtime-tunable dial) OR, if it is genuinely "
                        + "deploy-time/bootstrap, add it to ENV_VAR_BOOTSTRAP with a justification.%n%s",
                        stranded.stream().map(key -> "  - " + key + "  (read at " + reads.get(key) + ")")
                                .collect(Collectors.joining(System.lineSeparator())))
                .isEmpty();
    }

    @Test
    void the_env_var_bootstrap_allowlist_stays_live_and_distinct_from_the_settings_catalogue() throws IOException {
        Map<String, String> reads = readConfigKeys(sourceRoot());
        Set<String> declared = declaredKeys(sourceRoot());

        // No dead allowlist entry: every bootstrap key must actually be read somewhere, so a stale or mistyped entry
        // cannot silently mask a future stranded key or rot as the code changes.
        List<String> unread = ENV_VAR_BOOTSTRAP.keySet().stream()
                .filter(key -> !reads.containsKey(key))
                .sorted()
                .toList();
        assertThat(unread)
                .as("these bootstrap-allowlisted keys are no longer read anywhere - remove them from "
                        + "ENV_VAR_BOOTSTRAP so the allowlist tracks the code")
                .isEmpty();

        // The two paths are distinct: a key that is both a declared runtime setting and an env-only bootstrap key is a
        // contradiction - decide which path owns it.
        List<String> both = ENV_VAR_BOOTSTRAP.keySet().stream()
                .filter(declared::contains)
                .sorted()
                .toList();
        assertThat(both)
                .as("these keys are declared as runtime settings AND allowlisted as env-only bootstrap - the two "
                        + "sanctioned paths are mutually exclusive, so pick one")
                .isEmpty();
    }

    /** Every key declared through the free settings catalogue (path b): the source scan of {@code new Setting("...")}
     *  literals across {@code source/} unioned with the runtime {@link SettingsCatalogue#keys()} catalogue. */
    private static Set<String> declaredKeys(Path sourceRoot) throws IOException {
        Set<String> declared = new TreeSet<>(sourceDeclaredKeys(sourceRoot));
        declared.addAll(SettingsCatalogue.keys());
        return declared;
    }

    /** Every {@code new Setting("<key>")} literal declared across the module sources (comments stripped, so a key
     *  named only in prose never counts as declared and cannot mask a stranded key). */
    private static Set<String> sourceDeclaredKeys(Path sourceRoot) throws IOException {
        Pattern declaration = Pattern.compile("new Setting\\(\"([^\"]+)\"");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(ConfigPrincipleTest::isJava)::iterator) {
                Matcher matcher = declaration.matcher(stripComments(Files.readString(file)));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        return keys;
    }

    /** Every config-key literal read across the module sources, mapped to a representative read site
     *  ({@code <relative-path>:<line>}). Captures both the direct {@code .apply("<key>")} lookup and the
     *  {@code <helper>(config, "<key>", ...)} reader idiom; the single literal-{@code config} false positive from
     *  {@code Objects.requireNonNull(config, "config")} is skipped. Over-approximates safely: a false positive that
     *  happens to be a declared setting simply passes. */
    private static Map<String, String> readConfigKeys(Path sourceRoot) throws IOException {
        Pattern apply = Pattern.compile("\\.apply\\(\"([^\"]+)\"");
        Pattern helper = Pattern.compile("\\(config,\\s*\"([^\"]+)\"");
        Map<String, String> keys = new TreeMap<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(ConfigPrincipleTest::isJava)::iterator) {
                // Strip comments (preserving newlines and string literals) so a `.apply("...")` written inside a
                // javadoc {@code ...} example is never mistaken for a real read - the same guard the sibling
                // ImmutabilityPrincipleTest applies. Line numbers survive because stripping keeps every newline.
                List<String> lines = List.of(stripComments(Files.readString(file)).split("\n", -1));
                for (int i = 0; i < lines.size(); i++) {
                    record Read(Pattern pattern, String line) {}
                    for (Read read : List.of(new Read(apply, lines.get(i)), new Read(helper, lines.get(i)))) {
                        Matcher matcher = read.pattern().matcher(read.line());
                        while (matcher.find()) {
                            String key = matcher.group(1);
                            if (key.equals("config")) {
                                continue;             // Objects.requireNonNull(config, "config") - a field name, not a key
                            }
                            keys.putIfAbsent(key, sourceRoot.relativize(file) + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        return keys;
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    /** Blanks out {@code //} and {@code /* *}{@code /} comments (preserving newlines and string/char literals) so the
     *  scans never trip on a config-key literal that appears inside a javadoc example rather than in real code. Ported
     *  from the sibling {@code ImmutabilityPrincipleTest} guard. */
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
