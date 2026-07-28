package build.jenesis.repository.server;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.TenantsProvider;

import module java.base;

/**
 * Publishes this node's {@link NodeFingerprint} to the shared store on a heartbeat (WCON.2). A stable node id is
 * derived once - the {@code jenesis.consistency.node-id} setting if given, else the hostname, else a generated
 * per-process id - and held as <em>instance</em> state on this bean (never a mutable static), so a fleet of in-process
 * nodes in a test each carry their own identity. A daemon scheduler re-publishes every heartbeat interval, so a node's
 * liveness (and its current config generation, cursor position and sampled counters) stays fresh for the fleet to
 * compare against; the write is a single compare-and-set on this node's own key, so it never contends with another node.
 *
 * <p>The fingerprint is cheap to build - the config generation is a hash over the must-match settings, the counters are
 * read from the store's own in-memory meter where present, and nothing walks the artifact namespace. Publishing is
 * best-effort: a write refused by a read-only deployment, or a transient store error, is logged at debug and retried on
 * the next heartbeat rather than failing the node. In the free core the derived-index cursor is not maintained (there is
 * no background sweep here), so it is published as zero with the heartbeat as its advance time - honest for a single
 * hosted node; a distribution that runs a real index sweep publishes its live cursor and freeze time.
 */
public final class NodeFingerprintPublisher implements AutoCloseable {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(NodeFingerprintPublisher.class);

    private final NodeConsistency consistency;
    private final ArtifactStore store;
    private final boolean enabled;
    private final String nodeId;
    private final long configGeneration;
    private final long heartbeatMillis;
    private final ScheduledExecutorService scheduler;

    public NodeFingerprintPublisher(NodeConsistency consistency, ArtifactStore store, UnaryOperator<String> config) {
        this.consistency = Objects.requireNonNull(consistency, "consistency");
        this.store = Objects.requireNonNull(store, "store");
        // Opt-in per deployment, like the other operational writers (demo seeding, batch ingestion): a single-node
        // deployment publishes nothing, so it never writes an operational key into an otherwise-clean store layout; a
        // multi-node deployment sets jenesis.consistency.enabled=true so its nodes publish and can be compared.
        this.enabled = "true".equalsIgnoreCase(String.valueOf(config.apply("jenesis.consistency.enabled")));
        this.nodeId = resolveNodeId(config);
        this.configGeneration = NodeFingerprint.configGeneration(mustMatch(config), tenantSet(store, config));
        this.heartbeatMillis = Math.max(1000L, millis(config, "jenesis.consistency.heartbeat",
                consistency.settings().sweepIntervalMillis()));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jenesis-consistency-" + nodeId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** This node's stable id, so a caller (the admin API) can name the node it is running on. */
    public String nodeId() {
        return nodeId;
    }

    /** Whether this node publishes its fingerprint - opt-in via {@code jenesis.consistency.enabled}. */
    public boolean enabled() {
        return enabled;
    }

    /** Publish once immediately, then on every heartbeat - Spring's {@code initMethod}. A disabled deployment does
     *  nothing, so it never writes into an otherwise-clean store; the read surfaces still work and report no node. */
    public void start() {
        if (!enabled) {
            return;
        }
        publishQuietly();
        scheduler.scheduleAtFixedRate(this::publishQuietly, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    /** Build and publish this node's current fingerprint; never throws (best-effort heartbeat). */
    public void publishQuietly() {
        try {
            consistency.publish(fingerprint());
        } catch (IOException | RuntimeException best) {
            // A read-only deployment refuses the write, or the store hiccuped - retry on the next heartbeat rather than
            // fail the node. Consistency detects and reports; it never blocks the node it runs on.
            LOGGER.debug("consistency fingerprint publish skipped for node {}: {}", nodeId, best.toString());
        }
    }

    /** This node's current fingerprint - the config generation fixed at boot, the counters read cheaply now. */
    NodeFingerprint fingerprint() {
        long now = System.currentTimeMillis();
        return new NodeFingerprint(nodeId, now, now, 0L, "", configGeneration, 0L, quotaUsed(), Map.of());
    }

    /** The bytes counted against the quota where the store meters them, else zero - a counter already in memory, never
     *  a scan. */
    private long quotaUsed() {
        try {
            return store instanceof QuotaArtifactStore quota ? quota.used() : 0L;
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** The settings that must be byte-for-byte identical on every node, so a differing value on any is a real split -
     *  the store backend, the routed tenant / repository, the authorization mode and the read-only flag. */
    private static Map<String, String> mustMatch(UnaryOperator<String> config) {
        Map<String, String> settings = new TreeMap<>();
        for (String key : List.of("jenesis.repository.store", "jenesis.repository.tenant",
                "jenesis.repository.repository", "jenesis.repository.auth", "jenesis.repository.read-only")) {
            String value = config.apply(key);
            settings.put(key, value == null ? "" : value);
        }
        return settings;
    }

    /** This deployment's tenant set, read once at boot through the same {@code TenantsProvider} seam the rest of the
     *  free core resolves the {@link build.jenesis.repository.store.Tenants} directory through: the single configured
     *  tenant with no tenants module installed, the store-backed scopes with one. Folded into the config generation so
     *  two nodes that route the same config but keep different tenant directories are caught as inconsistent (WCON.2),
     *  with multi-tenancy riding this one seam rather than a parallel fingerprint. Best-effort like the heartbeat write:
     *  if the directory cannot be listed, fall back to the single configured tenant so the fold stays stable rather than
     *  failing the node. */
    private static Collection<String> tenantSet(ArtifactStore store, UnaryOperator<String> config) {
        String tenant = config.apply("jenesis.repository.tenant");
        if (tenant == null || tenant.isBlank()) {
            tenant = "default";
        }
        try {
            return TenantsProvider.resolve(store, config, tenant).list();
        } catch (IOException | RuntimeException unavailable) {
            LOGGER.debug("consistency tenant-set read fell back to the configured tenant '{}': {}", tenant,
                    unavailable.toString());
            return List.of(tenant);
        }
    }

    /** A stable node id: the explicit setting, else the hostname, else a generated per-process id (with a warning that
     *  a stable id is preferable so a restart does not leave an orphan fingerprint object behind). */
    private static String resolveNodeId(UnaryOperator<String> config) {
        String configured = config.apply("jenesis.consistency.node-id");
        if (configured != null && !configured.isBlank()) {
            return sanitize(configured.trim());
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return sanitize(host.trim());
            }
        } catch (UnknownHostException noHost) {
            // fall through to a generated id
        }
        String generated = "node-" + Long.toHexString(UUID.randomUUID().getMostSignificantBits() & 0xffffffffL);
        LOGGER.warn("SECURITY/OPS: jenesis.consistency.node-id is unset and the hostname is unavailable, so this node "
                + "uses a generated per-process id ({}). Set a stable jenesis.consistency.node-id so a restart re-uses "
                + "the same identity instead of leaving an orphan fingerprint behind.", generated);
        return generated;
    }

    /** Reduce an id to a traversal-free key segment, so it is safe as the {@code consistency/nodes/<id>} key. */
    private static String sanitize(String id) {
        String cleaned = id.replaceAll("[^A-Za-z0-9_.-]", "-");
        return cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..") ? "node" : cleaned;
    }

    private static long millis(UnaryOperator<String> config, String key, long fallback) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException unparseable) {
            return fallback;
        }
    }
}
