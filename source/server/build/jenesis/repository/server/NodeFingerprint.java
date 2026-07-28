package build.jenesis.repository.server;

import module java.base;

/**
 * A lightweight, self-published snapshot of one node's derived in-memory view (WCON.2). A deployment runs N nodes over
 * one shared {@link build.jenesis.repository.store.ArtifactStore}, each with its own caches, derived-index cursor and
 * snapshot, quota / inventory counters and config generation - eventually consistent by design. Each node writes this
 * fingerprint to the shared store under {@code consistency/nodes/<id>} on a heartbeat, and the {@link NodeConsistency}
 * check compares the fingerprints across live nodes to tell benign lag (a node a little behind, still advancing) from a
 * node <em>stuck diverged</em> (a wedged sweep, a missed config change, a lost lease). It is a cheap value, not a scan:
 * every field is a counter already in memory or a small sampled set, never a walk of the store.
 *
 * <p>The two timestamps are distinct on purpose. {@link #heartbeatMillis} is liveness - when the node last published,
 * so a check can drop a dead node from the comparison. {@link #cursorAdvancedMillis} is progress - when the node's
 * derived-index cursor last moved forward; a wedged node keeps heartbeating (it is alive) while its cursor freezes, and
 * it is exactly that gap - alive but not advancing past {@code N} sweep intervals while behind the fleet - that marks a
 * node stuck rather than merely lagging.
 *
 * <p>{@link #configGeneration} is a hash over the settings that <em>must</em> be identical on every node (the store
 * backend, the routed tenant / repository, the authorization mode, the read-only flag); two live nodes disagreeing on
 * it is a real split - a missed config change - not lag. {@link #pointers} is a small sampled set of pointer -&gt; hash
 * resolutions (never the whole namespace) so the check can catch two nodes that resolve the same pointer to different
 * content, the sharpest form of "disagreeing on what must be identical". Immutable; the maps are defensively copied.
 */
public record NodeFingerprint(String nodeId, long heartbeatMillis, long cursorAdvancedMillis, long indexCursor,
                              String snapshotVersion, long configGeneration, long inventoryTotal, long quotaUsed,
                              Map<String, String> pointers) {

    public NodeFingerprint {
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        snapshotVersion = snapshotVersion == null ? "" : snapshotVersion;
        pointers = pointers == null ? Map.of() : Map.copyOf(pointers);
    }

    /** How long ago (ms) this node last published, measured from {@code now} - its liveness age. */
    public long heartbeatAgeMillis(long now) {
        return now - heartbeatMillis;
    }

    /** How long (ms) this node's derived-index cursor has been frozen, measured from {@code now} - the progress gap a
     *  wedged sweep opens up while the node keeps heartbeating. */
    public long stalledForMillis(long now) {
        return now - cursorAdvancedMillis;
    }

    /**
     * A stable {@code configGeneration} hash over the settings that must be byte-for-byte identical on every node. The
     * caller passes the effective values under the keys that matter; a differing value on any key yields a different
     * generation, so two nodes that disagree are caught without shipping (or leaking) the values themselves. Order
     * independent (the entries are sorted), so two nodes that computed the same settings in any order agree.
     *
     * <p>This config-only overload folds no tenant set; it is the exact byte form the check has always hashed, so a
     * caller that has no tenant directory to fold (a plain config comparison, the existing tests) is unchanged.
     */
    public static long configGeneration(Map<String, String> mustMatch) {
        return configGeneration(mustMatch, List.of());
    }

    /**
     * The same stable generation, additionally folding the deployment's <em>tenant set</em> into the hash (WCON.2).
     * Beyond the must-match settings, two nodes that route the same config but keep <em>different tenant directories</em>
     * are a real split - a multi-tenant edition where one node has grown a tenant the other has not is inconsistent even
     * though every config key matches. The free core reads this set from the {@link build.jenesis.repository.store.Tenants}
     * directory the {@code TenantsProvider} SPI resolves (the single configured tenant with no tenants module installed,
     * the store-backed scopes with one), so multi-tenancy rides that one seam rather than a parallel fingerprint.
     *
     * <p>The tenant set is sorted and de-duplicated (a {@link TreeSet}), so two nodes that discovered the same tenants in
     * any order agree, and it is namespaced under a {@code tenant/} line no config key can spell, so it can never collide
     * with a config contribution. An empty set appends nothing, so this reduces byte-for-byte to the config-only form -
     * a single-default-tenant free deployment folds exactly its one tenant and every free node agrees.
     */
    public static long configGeneration(Map<String, String> mustMatch, Collection<String> tenants) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(mustMatch).forEach((key, value) ->
                canonical.append(key).append('=').append(value == null ? "" : value).append('\n'));
        if (tenants != null && !tenants.isEmpty()) {
            new TreeSet<>(tenants).forEach(tenant ->
                    canonical.append("tenant/").append(tenant == null ? "" : tenant).append('\n'));
        }
        // A 64-bit FNV-1a over the canonical form: cheap, dependency-free and stable across runs (String.hashCode is
        // stable too, but a wider hash makes an accidental collision between two distinct configs vanishingly unlikely).
        long hash = 0xcbf29ce484222325L;
        byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
