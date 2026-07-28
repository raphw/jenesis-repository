package build.jenesis.repository.server;

import module java.base;

/**
 * A free-core extension point for the deployment-wide {@code /api/capabilities} surface, discovered at runtime with
 * {@link ServiceLoader} - so a richer distribution advertises its extra capabilities (an enterprise edition's supported
 * formats, import sources, module flags) on the <em>one</em> free-served {@code /api/capabilities} endpoint without a
 * bean override and without a client change. This is exactly the intent the free {@link RepositoryController#capabilities}
 * javadoc has always stated: "a distribution with more capabilities extends the map without a client change".
 *
 * <p>The free {@link RepositoryController} builds its base map ({@code readOnly}, {@code auth}, {@code anonymousRights}),
 * then {@link #merge merges} every discovered contributor into it. With no contributor installed - the free product -
 * the served map is exactly the base map, byte-for-byte unchanged. A distribution adds capabilities simply by shipping
 * a module that {@code provides build.jenesis.repository.server.CapabilityContributor with ...}; the server already
 * {@code uses} it, so no core change is needed. It replaces the former {@code WebMvcRegistrations} mapping-suppression
 * stopgap that dropped the free mapping so an enterprise controller could own the same path.
 *
 * <h2>Merge / precedence rule</h2>
 * Contributors <b>extend</b> the base map; they never shadow it. On a key conflict the <b>base key always wins</b>, and
 * among contributors the <b>first discovered wins</b> (see {@link #merge}). This guarantees the free product's own
 * flags - the read-only gate, the auth flag, the anonymous grant - can never be overwritten by a contributor, so the
 * base semantics of {@code /api/capabilities} are preserved whatever a distribution adds. New (non-conflicting) keys are
 * appended after the base keys, in contributor discovery order.
 */
public interface CapabilityContributor {

    /**
     * The capabilities this contributor adds to {@code /api/capabilities}. Values must be JSON-serialisable (a boolean,
     * a string, a number, a list or a nested map), the same shape the base map uses. Returning an empty map (or
     * {@code null}) contributes nothing. Never mutate the map passed to {@link #merge}; return a fresh map of the extra
     * entries.
     */
    Map<String, Object> capabilities();

    /**
     * Merge every {@code contributor}'s {@link #capabilities()} into a copy of {@code base}, applying the documented
     * precedence rule: a base key always wins a conflict, and among contributors the first discovered wins. The base
     * keys keep their insertion order first; new keys are appended in contributor discovery order. When
     * {@code contributors} is empty the returned map equals {@code base} exactly (same keys, same order, same values) -
     * the free product's byte-for-byte-unchanged guarantee.
     */
    static Map<String, Object> merge(Map<String, Object> base, Iterable<CapabilityContributor> contributors) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        for (CapabilityContributor contributor : contributors) {
            Map<String, Object> contribution = contributor.capabilities();
            if (contribution == null) {
                continue;
            }
            // putIfAbsent enforces the precedence rule in one pass: an existing base key (or an earlier contributor's
            // key) is kept, a genuinely new key is appended - so the base map can never be shadowed by a contributor.
            contribution.forEach(merged::putIfAbsent);
        }
        return merged;
    }
}
