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

    /** A named OIDC trust: an id-token from {@code issuer} (signed by its JWKS) whose {@code audience} and
     *  {@code subject} (a glob, blank for any) match is exchanged for a short-lived credential of {@code ttl} carrying
     *  {@code rights} on {@code scope}. A blank {@code audience} accepts any audience. */
    public record Trust(String name, String issuer, String audience, String subject, String scope, String rights,
                        Duration ttl) {
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

    /** A tenant's OIDC trusts, by name. An id-token matching a trust is exchanged for a short-lived credential. */
    public List<Trust> trusts(String tenant) throws IOException {
        Properties stored = store == null ? null : read(oidcPath(tenant));
        if (stored == null) {
            return List.of();
        }
        Set<String> names = new TreeSet<>();
        for (String key : stored.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                names.add(key.substring(0, dot));
            }
        }
        List<Trust> trusts = new ArrayList<>();
        for (String name : names) {
            String ttl = stored.getProperty(name + ".ttl");
            trusts.add(new Trust(name, stored.getProperty(name + ".issuer"),
                    stored.getProperty(name + ".audience"), stored.getProperty(name + ".subject"),
                    stored.getProperty(name + ".scope"), stored.getProperty(name + ".rights"),
                    ttl == null || ttl.isBlank() ? null : Duration.parse(ttl)));
        }
        return trusts;
    }

    /** Add or replace an OIDC trust by name; {@code issuer}, {@code scope} and {@code rights} are required. */
    public void setTrust(String tenant, Trust trust) throws IOException {
        require();
        if (trust.name() == null || trust.name().isBlank() || trust.issuer() == null || trust.issuer().isBlank()
                || trust.scope() == null || trust.scope().isBlank() || trust.rights() == null || trust.rights().isBlank()) {
            throw new IllegalArgumentException("An OIDC trust needs a name, an issuer, a scope and rights");
        }
        Properties stored = read(oidcPath(tenant));
        if (stored == null) {
            stored = new Properties();
        }
        String name = trust.name();
        stored.setProperty(name + ".issuer", trust.issuer());
        stored.setProperty(name + ".audience", trust.audience() == null ? "" : trust.audience());
        stored.setProperty(name + ".subject", trust.subject() == null ? "" : trust.subject());
        stored.setProperty(name + ".scope", trust.scope());
        stored.setProperty(name + ".rights", trust.rights());
        stored.setProperty(name + ".ttl", (trust.ttl() == null ? Duration.ofHours(1) : trust.ttl()).toString());
        write(oidcPath(tenant), stored);
    }

    /** Remove a tenant's OIDC trust by name. */
    public void removeTrust(String tenant, String name) throws IOException {
        require();
        Properties stored = read(oidcPath(tenant));
        if (stored == null) {
            return;
        }
        for (String key : new ArrayList<>(stored.stringPropertyNames())) {
            if (key.startsWith(name + ".")) {
                stored.remove(key);
            }
        }
        write(oidcPath(tenant), stored);
    }

    /** A tenant's named roles (a role bundles grant tokens), built-in defaults overlaid with stored custom roles. The
     *  defaults form a hierarchy - read-only reads, deploy adds writes, admin grants everything - so a console can
     *  offer a friendly role instead of raw {@code <surface>:<verb>} tokens. A custom role may override a default. */
    public Map<String, String> roles(String tenant) throws IOException {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("read-only", "cache:read,repository:read");
        roles.put("deploy", "cache:read,cache:write,repository:read,repository:write");
        roles.put("admin", "*");
        Properties stored = store == null ? null : read(rolesPath(tenant));
        if (stored != null) {
            for (String name : stored.stringPropertyNames()) {
                roles.put(name, stored.getProperty(name));
            }
        }
        return roles;
    }

    /** Add or replace a custom role on a tenant; a built-in name can be overridden. */
    public void setRole(String tenant, String name, String tokens) throws IOException {
        require();
        if (name == null || name.isBlank() || tokens == null || tokens.isBlank()) {
            throw new IllegalArgumentException("A role needs a name and tokens");
        }
        Properties stored = read(rolesPath(tenant));
        if (stored == null) {
            stored = new Properties();
        }
        stored.setProperty(name.trim(), tokens.trim());
        write(rolesPath(tenant), stored);
    }

    /** Remove a stored custom role (a built-in default reappears unless it was overriding one). */
    public void removeRole(String tenant, String name) throws IOException {
        require();
        Properties stored = read(rolesPath(tenant));
        if (stored == null) {
            return;
        }
        stored.remove(name);
        write(rolesPath(tenant), stored);
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
     *  comma-separated tokens}). {@code label}, {@code expires}, {@code lastUsed}, {@code lastUsedAddress} and
     *  {@code allowedAddresses} (a comma-separated source-IP allowlist) may be {@code null}; {@code useCount} is the
     *  running number of authorized uses. */
    public record Credential(String hash, String label, Instant created, Instant expires, Instant lastUsed,
                             String lastUsedAddress, long useCount, String allowedAddresses,
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
        String allowedAddresses = metadata == null ? null : metadata.getProperty("allowed-ips");
        String lastUsedAddress = metadata == null ? null : metadata.getProperty("lastUsedAddress");
        long useCount = metadata == null ? 0 : Long.parseLong(metadata.getProperty("useCount", "0"));
        return Optional.of(new Credential(hash, label,
                instant(metadata, "created"), instant(metadata, "expires"), instant(metadata, "lastUsed"),
                lastUsedAddress, useCount, allowedAddresses, scopes));
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

    /** Set or clear ({@code null}/blank) a credential's source-IP allowlist: comma-separated CIDRs or plain
     *  addresses. A request whose source address lies in none of them is forbidden even with a valid key. */
    public void setAllowedAddresses(String tenant, String hash, String addresses) throws IOException {
        require();
        Properties metadata = read(metadataPath(tenant, hash));
        if (metadata == null) {
            metadata = new Properties();
            metadata.setProperty("created", Instant.now().toString());
        }
        if (addresses == null || addresses.isBlank()) {
            metadata.remove("allowed-ips");
        } else {
            metadata.setProperty("allowed-ips", addresses.trim());
        }
        write(metadataPath(tenant, hash), metadata);
    }

    /** Whether a credential's source-IP allowlist admits {@code clientAddress}: true when it sets none (the common
     *  case) and when the address falls in a listed CIDR or matches a listed plain address; false otherwise, so a
     *  stolen key is useless off its network. A malformed or unprovisioned key is left to the grant check. */
    public boolean addressAllowed(String key, String clientAddress) throws IOException {
        if (store == null || !wellFormed(key)) {
            return true;
        }
        Properties metadata = read(metadataPath(tenantOf(key), hash(key)));
        String allowed = metadata == null ? null : metadata.getProperty("allowed-ips");
        if (allowed == null || allowed.isBlank()) {
            return true;
        }
        if (clientAddress == null) {
            return false;
        }
        for (String cidr : allowed.split(",")) {
            if (inRange(clientAddress.trim(), cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code address} lies within {@code cidr} (a {@code network/bits} range or a plain address), comparing
     *  the leading bits of the two addresses; mismatched IPv4/IPv6 families or unparseable input never match. */
    private static boolean inRange(String address, String cidr) {
        try {
            int slash = cidr.indexOf('/');
            byte[] network = InetAddress.getByName(slash < 0 ? cidr : cidr.substring(0, slash)).getAddress();
            byte[] candidate = InetAddress.getByName(address).getAddress();
            if (network.length != candidate.length) {
                return false;
            }
            int bits = slash < 0 ? network.length * 8 : Integer.parseInt(cidr.substring(slash + 1).trim());
            int fullBytes = bits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != candidate[i]) {
                    return false;
                }
            }
            int remainder = bits % 8;
            if (remainder > 0) {
                int mask = (0xFF << (8 - remainder)) & 0xFF;
                return (network[fullBytes] & mask) == (candidate[fullBytes] & mask);
            }
            return true;
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    /** The real client address for a request given its TCP {@code peer} and any {@code X-Forwarded-For}. A forwarded
     *  header is trusted only when {@code peer} is itself one of {@code trustedProxies}: then the rightmost forwarded
     *  hop that is not also a trusted proxy is the client (walking back through the proxy chain). Otherwise the peer is
     *  the client - a forwarded header from an untrusted source is ignored, so the source-IP allowlist cannot be
     *  spoofed by a client that sets its own {@code X-Forwarded-For}. */
    public static String clientAddress(String peer, String forwardedFor, List<String> trustedProxies) {
        if (peer == null || trustedProxies == null || trustedProxies.isEmpty() || !withinAny(peer, trustedProxies)) {
            return peer;
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !withinAny(hop, trustedProxies)) {
                return hop;
            }
        }
        return peer;
    }

    private static boolean withinAny(String address, List<String> cidrs) {
        for (String cidr : cidrs) {
            if (inRange(address, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Stamp a credential's last use - the time, the source {@code address} (kept when {@code null}) and a count
     *  raised by {@code increment} - for the off-request usage tracker, which batches so the store sees at most one
     *  write per credential per day. A revoked credential (no metadata) is silently skipped. */
    public void recordUsed(String tenant, String hash, Instant when, String address, long increment)
            throws IOException {
        require();
        Properties metadata = read(metadataPath(tenant, hash));
        if (metadata == null) {
            return;
        }
        metadata.setProperty("lastUsed", when.toString());
        if (address != null) {
            metadata.setProperty("lastUsedAddress", address);
        }
        metadata.setProperty("useCount",
                Long.toString(Long.parseLong(metadata.getProperty("useCount", "0")) + increment));
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

    /** A freshly minted successor key and the expiry stamped on it. */
    public record Rotated(String key, Instant expires) {
    }

    /** Rotate a credential: mint a successor that inherits the same label, grants and source-IP allowlist with a fresh
     *  default lifetime, and set the old credential to expire after {@code overlap} (7 days when null) so callers can
     *  swap over with no downtime. Returns the successor's raw key (shown once); the old hash keeps working until the
     *  overlap elapses, then expires on its own. */
    public Rotated rotate(String tenant, String hash, Duration overlap) throws IOException {
        require();
        Credential previous = credential(tenant, hash).orElseThrow(
                () -> new IllegalArgumentException("No such credential to rotate"));
        String key = mint(tenant);
        String successor = hash(key);
        Instant expires = mintExpiry(tenant, null, false);
        provision(tenant, successor, previous.label(), expires);
        for (Map.Entry<String, String> grant : previous.grants().entrySet()) {
            setGrant(tenant, successor, grant.getKey(), grant.getValue());
        }
        if (previous.allowedAddresses() != null) {
            setAllowedAddresses(tenant, successor, previous.allowedAddresses());
        }
        setExpiry(tenant, hash, Instant.now().plus(overlap == null ? Duration.ofDays(7) : overlap));
        return new Rotated(key, expires);
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

    private static String oidcPath(String tenant) {
        return "auth/" + tenant + "/oidc";
    }

    private static String rolesPath(String tenant) {
        return "auth/" + tenant + "/roles";
    }
}
