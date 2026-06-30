package build.jenesis.repository;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The credential model. A key is {@code jenk_<tenant>.<secret><checksum>} (see {@link #mint}): the {@code jenk_}
 * prefix and trailing checksum make a leaked key recognisable and offline-validatable by a secret scanner, the
 * tenant travels in the key so the deployment stays stateless and multi-tenant, and only the key's SHA-256 hash is
 * ever stored, never the secret. Under
 * {@code auth/<tenant>/<hash>/} sit two small objects: {@code grants} - a properties map of
 * {@code <scope> -> <rights>} where the scope is a named repository ({@code *} matching all) and the rights are
 * {@code <surface>:<verb>} tokens - and {@code metadata} (label, created, optional expiry, optional last-used).
 * The lookup tries the exact scope, then falls back to {@code *}; a re-read picks up a revoked grant at once because
 * the objects are read through the store on each check, and an expired key is rejected before its grants are
 * consulted.
 *
 * The rights a credential can carry are named here as {@code <surface>:<verb>} string constants rather than a closed
 * enum, so a deployment or a plugged-in surface can introduce a new surface (or a new verb on one) without changing
 * this type. Because a right names its surface, the same key (and the same grants object) can carry repository
 * rights, cache rights, management rights, or any mix, which is how one credential authorizes a combined deployment.
 * This class is the single store for all of them; each surface reads the same key against the same grants.
 */
public final class Authorization {

    public static final String CACHE_READ = "cache:read";

    public static final String CACHE_WRITE = "cache:write";

    public static final String REPOSITORY_READ = "repository:read";

    public static final String REPOSITORY_WRITE = "repository:write";

    public static final String MANAGE_READ = "manage:read";

    public static final String MANAGE_WRITE = "manage:write";

    private final ArtifactStore store;
    private final Duration defaultLifetime;
    private final Duration maxLifetime;

    private Authorization(ArtifactStore store) {
        this(store, Duration.ofDays(90), null);
    }

    private Authorization(ArtifactStore store, Duration defaultLifetime, Duration maxLifetime) {
        this.store = store;
        this.defaultLifetime = defaultLifetime;
        this.maxLifetime = maxLifetime;
    }

    /** An open deployment: every request is allowed, the headless default for the free single-token deployment. */
    public static Authorization anonymous() {
        return new Authorization(null);
    }

    /** An enforcing deployment: every request is checked against the grants held in {@code store}. */
    public static Authorization enforcing(ArtifactStore store) {
        if (store == null) {
            throw new IllegalArgumentException("An enforcing authorization needs a store to read grants from");
        }
        return new Authorization(store);
    }

    /** The deployment-wide default lifetime for a credential minted without an explicit expiry (90 days unless
     *  overridden); a tenant policy may narrow it further (see {@link #policy}). */
    public Authorization withDefaultLifetime(Duration defaultLifetime) {
        if (defaultLifetime == null || defaultLifetime.isZero() || defaultLifetime.isNegative()) {
            throw new IllegalArgumentException("A default credential lifetime must be a positive duration");
        }
        return new Authorization(store, defaultLifetime, maxLifetime);
    }

    /** The deployment-wide ceiling on a credential's lifetime (none unless set); no credential of any tenant may
     *  outlive it, and a tenant policy can only cap further, never beyond it. */
    public Authorization withMaxLifetime(Duration maxLifetime) {
        if (maxLifetime != null && (maxLifetime.isZero() || maxLifetime.isNegative())) {
            throw new IllegalArgumentException("A maximum credential lifetime must be a positive duration");
        }
        return new Authorization(store, defaultLifetime, maxLifetime);
    }

    public Duration defaultLifetime() {
        return defaultLifetime;
    }

    public Duration maxLifetime() {
        return maxLifetime;
    }

    /** A tenant's effective credential-lifetime policy: the default to stamp on a blank-expiry mint and the optional
     *  ceiling beyond which no key may live. {@code maxLifetime} is {@code null} when nothing caps the lifetime. */
    public record Policy(Duration defaultLifetime, Duration maxLifetime) {
    }

    /** The effective policy for {@code tenant}: a stored per-tenant default/ceiling layered over the deployment-wide
     *  values, the ceiling being the stricter (shorter) of the two and the default never exceeding it. */
    public Policy policy(String tenant) throws IOException {
        Properties stored = store == null ? null : read(policyPath(tenant));
        Duration tenantDefault = duration(stored, "default-lifetime");
        Duration ceiling = shorter(duration(stored, "max-lifetime"), maxLifetime);
        Duration effectiveDefault = tenantDefault != null ? tenantDefault : defaultLifetime;
        if (ceiling != null && effectiveDefault.compareTo(ceiling) > 0) {
            effectiveDefault = ceiling;
        }
        return new Policy(effectiveDefault, ceiling);
    }

    /** Set or clear ({@code null}) a tenant's per-tenant default and maximum lifetimes; a value must be positive. */
    public void setPolicy(String tenant, Duration defaultLifetime, Duration maxLifetime) throws IOException {
        require();
        if (defaultLifetime != null && (defaultLifetime.isZero() || defaultLifetime.isNegative())) {
            throw new IllegalArgumentException("A default credential lifetime must be a positive duration");
        }
        if (maxLifetime != null && (maxLifetime.isZero() || maxLifetime.isNegative())) {
            throw new IllegalArgumentException("A maximum credential lifetime must be a positive duration");
        }
        Properties properties = new Properties();
        if (defaultLifetime != null) {
            properties.setProperty("default-lifetime", defaultLifetime.toString());
        }
        if (maxLifetime != null) {
            properties.setProperty("max-lifetime", maxLifetime.toString());
        }
        write(policyPath(tenant), properties);
    }

    /** The expiry to stamp on a newly minted credential for {@code tenant}. A non-null {@code requested} instant is
     *  honoured, a null request yields the tenant default from now, and {@code nonExpiring} asks for an unbounded key
     *  - granted only when no ceiling applies, otherwise pulled back to the ceiling. So a credential expires by
     *  default and never outlives the policy. */
    public Instant mintExpiry(String tenant, Instant requested, boolean nonExpiring) throws IOException {
        Policy policy = policy(tenant);
        Instant base = nonExpiring
                ? null
                : requested != null ? requested : Instant.now().plus(policy.defaultLifetime());
        return cap(base, policy.maxLifetime());
    }

    private static Instant cap(Instant expires, Duration maxLifetime) {
        if (maxLifetime == null) {
            return expires;
        }
        Instant ceiling = Instant.now().plus(maxLifetime);
        return expires == null || expires.isAfter(ceiling) ? ceiling : expires;
    }

    private static Duration shorter(Duration left, Duration right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) <= 0 ? left : right;
    }

    public boolean enforced() {
        return store != null;
    }

    /** The verdict for a request, mapped at the HTTP layer to 200/201, 401 and 403 respectively. */
    public enum Decision {
        ALLOWED,
        UNAUTHORIZED,
        FORBIDDEN
    }

    /** A credential as the management surface sees it: its hash, metadata and per-scope grants ({@code scope ->
     *  comma-separated tokens}). {@code label}, {@code expires} and {@code lastUsed} may be {@code null}. */
    public record Credential(String hash, String label, Instant created, Instant expires, Instant lastUsed,
                             Map<String, String> grants) {
    }

    /** Whether {@code key} carries {@code required} (a {@code <surface>:<verb>} token) for {@code scope} (a
     *  repository name, or {@code null} for the default); an expired key is {@code UNAUTHORIZED}. */
    public Decision authorize(String key, String scope, String required) throws IOException {
        return authorize(key, scope, null, required);
    }

    /** Whether {@code key} carries {@code required} for {@code scope} on the in-repository {@code path} ({@code null}
     *  when the surface has no path, as the cache). A grant scope is a repository name (or {@code *}), optionally
     *  narrowed to a path prefix as {@code <repo>:<prefix>} - a prefix grant covers the request only when {@code path}
     *  lies under it - so one credential can carry repository-wide and path-scoped rights at once. An expired key is
     *  {@code UNAUTHORIZED}, an unprovisioned one {@code FORBIDDEN}. */
    public Decision authorize(String key, String scope, String path, String required) throws IOException {
        if (store == null) {
            return Decision.ALLOWED;
        }
        if (!wellFormed(key)) {
            return Decision.UNAUTHORIZED;
        }
        String tenant = tenantOf(key);
        String hash = hash(key);
        Instant expires = instant(read(metadataPath(tenant, hash)), "expires");
        if (expires != null && Instant.now().isAfter(expires)) {
            return Decision.UNAUTHORIZED;
        }
        Properties grants = read(grantsPath(tenant, hash));
        if (grants == null) {
            return Decision.FORBIDDEN;
        }
        String repository = scope == null || scope.isBlank() ? "*" : scope;
        for (String grantScope : grants.stringPropertyNames()) {
            if (!covers(grantScope, repository, path)) {
                continue;
            }
            for (String token : grants.getProperty(grantScope).split(",")) {
                if (grantedBy(token, required)) {
                    return Decision.ALLOWED;
                }
            }
        }
        return Decision.FORBIDDEN;
    }

    /** Whether a grant scope - a repository ({@code *} matching any), optionally narrowed by a {@code :<prefix>} path
     *  prefix - covers a request for {@code repository} on {@code path}. A prefix grant matches only a non-null path
     *  at or under the prefix on a segment boundary, so {@code maven/com/acme} covers {@code maven/com/acme/x} but not
     *  {@code maven/com/acmexyz}; a bare repository grant covers any path. */
    private static boolean covers(String grantScope, String repository, String path) {
        int colon = grantScope.indexOf(':');
        String repositoryPart = colon < 0 ? grantScope : grantScope.substring(0, colon);
        if (!repositoryPart.equals("*") && !repositoryPart.equals(repository)) {
            return false;
        }
        if (colon < 0) {
            return true;
        }
        if (path == null) {
            return false;
        }
        String prefix = trimPath(grantScope.substring(colon + 1));
        String target = trimPath(path);
        return target.equals(prefix) || target.startsWith(prefix + "/");
    }

    private static String trimPath(String path) {
        String value = path.strip();
        if (value.endsWith("/*")) {
            value = value.substring(0, value.length() - 2);
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    /** Whether a granted token confers a required {@code <surface>:<verb>}: the exact token, the per-surface
     *  wildcard {@code <surface>:*}, or the all-privileges {@code *}. An unknown token confers nothing. */
    private static boolean grantedBy(String granted, String required) {
        String trimmed = granted.trim();
        if (trimmed.equals("*") || trimmed.equals(required)) {
            return true;
        }
        int colon = required.indexOf(':');
        return colon > 0 && trimmed.equals(required.substring(0, colon) + ":*");
    }

    /** Set {@code key}'s rights for {@code scope} by key, replacing any held for that scope; for provisioning when
     *  the secret is still in hand. */
    public void grant(String key, String scope, String... rights) throws IOException {
        setGrant(tenantOf(key), hash(key), scope, String.join(",", rights));
    }

    /** Grant every privilege for {@code scope} by key - the all-privileges {@code *} token, an owner/admin key. */
    public void grantAll(String key, String scope) throws IOException {
        setGrant(tenantOf(key), hash(key), scope, "*");
    }

    /** The credential hashes provisioned for a tenant, for the management surface to list. */
    public List<String> credentials(String tenant) {
        if (store == null) {
            return List.of();
        }
        return store.list("auth/" + tenant);
    }

    /** A credential's metadata and grants, or empty if neither is present. */
    public Optional<Credential> credential(String tenant, String hash) throws IOException {
        Properties grants = read(grantsPath(tenant, hash));
        Properties metadata = read(metadataPath(tenant, hash));
        if (grants == null && metadata == null) {
            return Optional.empty();
        }
        Map<String, String> scopes = new TreeMap<>();
        if (grants != null) {
            for (String scope : grants.stringPropertyNames()) {
                scopes.put(scope, grants.getProperty(scope));
            }
        }
        String label = metadata == null ? null : metadata.getProperty("label");
        return Optional.of(new Credential(hash, label,
                instant(metadata, "created"), instant(metadata, "expires"), instant(metadata, "lastUsed"), scopes));
    }

    /** Record a freshly minted credential's metadata (created now, an optional label and optional expiry); the
     *  caller has already hashed the key. Grants are added with {@link #setGrant}. */
    public void provision(String tenant, String hash, String label, Instant expires) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("created", Instant.now().toString());
        if (label != null && !label.isBlank()) {
            metadata.setProperty("label", label.trim());
        }
        if (expires != null) {
            metadata.setProperty("expires", expires.toString());
        }
        write(metadataPath(tenant, hash), metadata);
    }

    /** Set the rights for {@code scope} on a credential by hash, replacing any held for that scope. */
    public void setGrant(String tenant, String hash, String scope, String tokens) throws IOException {
        require();
        Properties grants = read(grantsPath(tenant, hash));
        if (grants == null) {
            grants = new Properties();
        }
        grants.setProperty(scope, tokens);
        write(grantsPath(tenant, hash), grants);
    }

    /** Remove the rights for {@code scope} on a credential by hash. */
    public void removeGrant(String tenant, String hash, String scope) throws IOException {
        require();
        Properties grants = read(grantsPath(tenant, hash));
        if (grants == null) {
            return;
        }
        grants.remove(scope);
        write(grantsPath(tenant, hash), grants);
    }

    /** Set or clear a credential's expiry; {@code null} removes it (the key no longer expires) unless the tenant
     *  policy caps the lifetime, in which case a cleared or too-distant expiry is pulled back to the ceiling. */
    public void setExpiry(String tenant, String hash, Instant expires) throws IOException {
        require();
        Instant capped = cap(expires, policy(tenant).maxLifetime());
        Properties metadata = read(metadataPath(tenant, hash));
        if (metadata == null) {
            metadata = new Properties();
            metadata.setProperty("created", Instant.now().toString());
        }
        if (capped == null) {
            metadata.remove("expires");
        } else {
            metadata.setProperty("expires", capped.toString());
        }
        write(metadataPath(tenant, hash), metadata);
    }

    /** Stamp a credential's last-used time; the off-request usage tracker calls this, at most once per day. */
    public void recordUsed(String tenant, String hash, Instant when) throws IOException {
        require();
        Properties metadata = read(metadataPath(tenant, hash));
        if (metadata == null) {
            return;
        }
        metadata.setProperty("lastUsed", when.toString());
        write(metadataPath(tenant, hash), metadata);
    }

    /** Revoke a credential by hash: delete its grants and metadata, so the next request is forbidden. */
    public void revoke(String tenant, String hash) throws IOException {
        require();
        store.delete(grantsPath(tenant, hash));
        store.delete(metadataPath(tenant, hash));
    }

    /** Revoke the credential a raw {@code key} resolves to, for when a key is reported leaked: the {@code jenk_}
     *  checksum and tenant are read straight off the key, so a malformed or unknown key revokes nothing. Returns
     *  whether a provisioned credential was actually revoked. */
    public boolean revokeLeaked(String key) throws IOException {
        if (!wellFormed(key)) {
            return false;
        }
        String tenant = tenantOf(key);
        String hash = hash(key);
        if (credential(tenant, hash).isEmpty()) {
            return false;
        }
        revoke(tenant, hash);
        return true;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A freshly minted key for {@code tenant}, in the scannable form {@code jenk_<tenant>.<secret><checksum>}. The
     * {@code jenk_} prefix and the trailing CRC32 checksum let a secret scanner recognise a leaked Jenesis key and
     * validate it offline (so a partner scanner can report it for revocation), and let the server
     * reject a malformed or truncated key with no store lookup. The tenant travels in the key so resolution stays
     * stateless; only the key's SHA-256 hash is ever stored.
     */
    public static String mint(String tenant) {
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        String body = "jenk_" + tenant + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return body + checksum(body);
    }

    /** Whether {@code key} is a well-formed Jenesis key - the {@code jenk_} prefix, a tenant, a secret and a matching
     *  trailing checksum - checked before any store lookup so a malformed or truncated key is cheap to reject. */
    public static boolean wellFormed(String key) {
        if (key == null || !key.startsWith("jenk_")) {
            return false;
        }
        int dot = key.indexOf('.');
        if (dot <= 5 || key.length() <= dot + 7) {
            return false;
        }
        int split = key.length() - 6;
        return key.substring(split).equals(checksum(key.substring(0, split)));
    }

    /** The tenant carried by a {@code jenk_}-prefixed key, or {@code null} if it is not one. */
    public static String tenantOf(String key) {
        if (key == null || !key.startsWith("jenk_")) {
            return null;
        }
        int dot = key.indexOf('.');
        return dot > 5 ? key.substring(5, dot) : null;
    }

    private static String checksum(String body) {
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        long value = crc.getValue();
        byte[] bytes = {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The lowercase hex SHA-256 of a key, the only form of it that is ever persisted. */
    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void require() {
        if (store == null) {
            throw new IllegalStateException("Cannot manage credentials on an anonymous authorization");
        }
    }

    private Properties read(String path) throws IOException {
        Optional<ArtifactStore.Versioned> object = store.readVersioned(path);
        if (object.isEmpty()) {
            return null;
        }
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(object.get().content()));
        return properties;
    }

    private void write(String path, Properties properties) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, null);
        store.write(path, new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static Instant instant(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return value == null ? null : Instant.parse(value);
    }

    private static Duration duration(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return value == null ? null : Duration.parse(value);
    }

    private static String grantsPath(String tenant, String hash) {
        return "auth/" + tenant + "/" + hash + "/grants";
    }

    private static String metadataPath(String tenant, String hash) {
        return "auth/" + tenant + "/" + hash + "/metadata";
    }

    private static String policyPath(String tenant) {
        return "auth/" + tenant + "/policy";
    }
}
