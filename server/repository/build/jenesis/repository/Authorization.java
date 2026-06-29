package build.jenesis.repository;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Right;

/**
 * The credential model. A key is {@code <tenant>.<secret>}: the tenant travels in
 * the key so the deployment stays stateless and multi-tenant, and only the key's SHA-256 hash is ever stored,
 * never the secret. Against that hash sits a grants object - a properties map of {@code <scope> -> <rights>} -
 * where the scope is a named repository ({@code *} matching all) and the rights are {@link Right} tokens. The
 * lookup tries the exact scope, then falls back to {@code *}; a re-read picks up a revoked grant at once because
 * the grants object is read through the store on each check.
 *
 * Because a right names its surface, the same key (and the same grants object) can carry repository rights, cache
 * rights, or both, which is how one credential authorizes a combined cache-and-repository deployment. This class
 * enforces the repository surface; the cache enforces its own, reading the same key against the same rights.
 */
public final class Authorization {

    private final ArtifactStore store;

    private Authorization(ArtifactStore store) {
        this.store = store;
    }

    /** An open repository: every request is allowed, the headless default for the free single-token deployment. */
    public static Authorization anonymous() {
        return new Authorization(null);
    }

    /** An enforcing repository: every request is checked against the grants held in {@code store}. */
    public static Authorization enforcing(ArtifactStore store) {
        if (store == null) {
            throw new IllegalArgumentException("An enforcing authorization needs a store to read grants from");
        }
        return new Authorization(store);
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

    /** Whether {@code key} carries {@code required} for {@code scope} (a repository name, or {@code null} for the default). */
    public Decision authorize(String key, String scope, Right required) throws IOException {
        if (store == null) {
            return Decision.ALLOWED;
        }
        if (key == null || key.isBlank()) {
            return Decision.UNAUTHORIZED;
        }
        int dot = key.indexOf('.');
        if (dot <= 0 || dot == key.length() - 1) {
            return Decision.UNAUTHORIZED;
        }
        Properties grants = read(key);
        if (grants == null) {
            return Decision.FORBIDDEN;
        }
        String tokens = grants.getProperty(scope == null || scope.isBlank() ? "*" : scope);
        if (tokens == null) {
            tokens = grants.getProperty("*");
        }
        if (tokens == null) {
            return Decision.FORBIDDEN;
        }
        for (String token : tokens.split(",")) {
            if (required.grantedBy(token)) {
                return Decision.ALLOWED;
            }
        }
        return Decision.FORBIDDEN;
    }

    /** Set {@code key}'s rights for {@code scope}, replacing any held for that scope; the admin/console write path. */
    public void grant(String key, String scope, Right... rights) throws IOException {
        List<String> tokens = new ArrayList<>();
        for (Right right : rights) {
            tokens.add(right.token());
        }
        write(key, scope, String.join(",", tokens));
    }

    /** Grant every privilege for {@code scope} - the all-privileges {@code *} token, an owner/admin key. */
    public void grantAll(String key, String scope) throws IOException {
        write(key, scope, "*");
    }

    private void write(String key, String scope, String tokens) throws IOException {
        if (store == null) {
            throw new IllegalStateException("Cannot grant on an anonymous authorization");
        }
        Properties grants = read(key);
        if (grants == null) {
            grants = new Properties();
        }
        grants.setProperty(scope, tokens);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        grants.store(bytes, null);
        store.write(path(key), new ByteArrayInputStream(bytes.toByteArray()));
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

    private Properties read(String key) throws IOException {
        Optional<ArtifactStore.Versioned> object = store.readVersioned(path(key));
        if (object.isEmpty()) {
            return null;
        }
        Properties grants = new Properties();
        grants.load(new ByteArrayInputStream(object.get().content()));
        return grants;
    }

    private static String path(String key) {
        int dot = key.indexOf('.');
        return "auth/" + (dot < 0 ? key : key.substring(0, dot)) + "/" + hash(key) + "/grants";
    }
}
