package build.jenesis.repository.server;

import module java.base;

/**
 * The free core's runtime-setting catalogue (#146): the product's built-in, runtime-tunable configuration dials,
 * declared once here so every key the code reads through the effective-config lookup ({@code config.apply("<key>")})
 * has a single, discoverable home. This is the free-core analogue of the enterprise {@code SettingsContributor} SPI
 * (a heavier, {@code ServiceLoader}-discovered, per-module contributor catalogue): the free product has one built-in
 * catalogue and no plugin-contributed settings, so a single {@link #ALL} list is the whole surface.
 *
 * <p>The structural {@code ConfigPrincipleTest} reads this catalogue - both by scanning the {@code new Setting("...")}
 * declarations (the same idiom the enterprise guard scans) and by unioning {@link #keys()} at runtime - to prove every
 * config key the code reads is either a declared runtime setting here or an allowlisted deploy-time bootstrap key. So a
 * key added the ordinary way with no home is caught at build time rather than silently stranded, unreachable without
 * hand-editing a store object.
 *
 * <p>Deploy-time / bootstrap keys are deliberately NOT here: the store backend and its credentials (the
 * {@code JENESIS_*} env), the fixed-tenant routing ({@code jenesis.repository.tenant}), the auth and read-only
 * deployment flags, and the per-node consistency enable/identity ({@code jenesis.consistency.enabled} /
 * {@code jenesis.consistency.node-id}, which are per-instance and cannot be one fleet-shared store setting). Those are
 * bound at startup from the environment/file configuration, not runtime-editable dials, and are the test's bootstrap
 * allowlist. A declared dial nobody reads is a dead dial, not a stranded key, and is out of the guard's scope.
 */
public final class SettingsCatalogue {

    /** One declared runtime setting: its effective-config key and a one-line human description. */
    public record Setting(String key, String description) {
    }

    /** Every runtime-tunable setting the free core reads through the effective-config lookup. */
    public static final List<Setting> ALL = List.of(
            // --- Multi-node consistency dials (WCON.2 / #146): the heartbeat cadence and the divergence-detection
            //     windows NodeFingerprintPublisher and NodeConsistency read through the effective-config lookup. The
            //     enable toggle and the node id are per-node deploy-time bootstrap, not dials, so they are on the
            //     test's bootstrap allowlist, not here. ---
            new Setting("jenesis.consistency.heartbeat",
                    "Milliseconds between this node's fingerprint publishes (the consistency heartbeat interval)"),
            new Setting("jenesis.consistency.staleness-window",
                    "Milliseconds a node may lag and still count as benign lag rather than stuck-diverged"),
            new Setting("jenesis.consistency.sweep-interval",
                    "Milliseconds between consistency sweeps (also the heartbeat fallback when the heartbeat is unset)"),
            new Setting("jenesis.consistency.sweep-intervals",
                    "How many frozen sweep intervals before a live-but-frozen node is reported stuck-diverged"),
            new Setting("jenesis.consistency.dead-after",
                    "Milliseconds after a node's last heartbeat before it is treated as dead and no longer compared"),

            // --- Pull-through proxy negative cache (source/proxy). ---
            new Setting("proxy-miss-ttl",
                    "How long a proxy negative-cache (upstream miss) entry is honoured before a re-fetch is allowed"),

            // --- Credential usage tracking (source/usage). ---
            new Setting("track-key-usage",
                    "Whether the batching key-usage tracker records each credential's last use and running count"),

            // --- Import-edge SSRF guard (ImportEdgeController). ---
            new Setting("block-private-import-hosts",
                    "Whether the free import edge refuses import targets that resolve to private/loopback hosts"),

            // --- Strictly-opt-in anonymous role (WANON.1). ---
            new Setting("anonymous-rights",
                    "The rights a keyless caller is granted under an enforcing deployment; blank (default) grants none"),

            // --- Garbage-collection dials (source/gc). ---
            new Setting("jenesis.gc.stride",
                    "Mark-sweep GC batch stride (number of objects scanned per pass)"),
            new Setting("jenesis.gc.grace",
                    "Grace period an unreferenced object survives before mark-sweep GC may reclaim it"),

            // --- Artifact-walk dials (source/walk). ---
            new Setting("jenesis.walk.checkpoint",
                    "Artifact-walk checkpoint interval (entries between resumable cursor writes)"),
            new Setting("jenesis.walk.segments",
                    "Artifact-walk parallel segment count"),
            new Setting("jenesis.walk.ttl",
                    "Seconds an artifact-walk cursor stays resumable before it expires"));

    private SettingsCatalogue() {
    }

    /** The declared setting keys, for a caller that only needs the key set (the {@code ConfigPrincipleTest} union). */
    public static Set<String> keys() {
        Set<String> keys = new TreeSet<>();
        for (Setting setting : ALL) {
            keys.add(setting.key());
        }
        return keys;
    }
}
