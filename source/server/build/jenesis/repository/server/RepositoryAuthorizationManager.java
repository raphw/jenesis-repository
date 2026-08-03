package build.jenesis.repository.server;
import build.jenesis.repository.server.spi.Authorization;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.util.UriUtils;

import module java.base;

/**
 * Authorizes a request against the {@link Authorization} credential model. An anonymous deployment (the headless
 * default) allows everything; an enforcing one reads the {@code Jenesis-Repository-Key} and optional
 * {@code Jenesis-Repository-Name} headers and requires {@code repository:read} for a GET/HEAD and
 * {@code repository:write} for any other method, on the router-resolved in-repository path so a path-scoped grant
 * ({@code <repo>:<prefix>}) authorizes exactly its subtree. The computed {@link Authorization.Decision} is recorded on the
 * request so {@link RepositoryAuthorizationEntryPoint} can answer {@code 401} for an unauthorized request (no key,
 * a malformed or expired key) and {@code 403} for a forbidden one (a key that lacks the right), regardless of which
 * Spring Security failure path the denial takes. It is contributed as a bean by
 * {@link RepositorySecurityAutoConfiguration}.
 */
public class RepositoryAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final Authorization authorization;
    private final RepositoryRouting routing;

    public RepositoryAuthorizationManager(Authorization authorization, RepositoryRouting routing) {
        this.authorization = authorization;
        this.routing = routing;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        if (!authorization.enforced()) {
            return new AuthorizationDecision(true);
        }
        HttpServletRequest request = context.getRequest();
        String method = request.getMethod();
        String required = method.equals("GET") || method.equals("HEAD")
                ? Authorization.REPOSITORY_READ
                : Authorization.REPOSITORY_WRITE;
        // Classify on the percent-DECODED, normalized path Spring actually routes on, not the raw request URI. Spring
        // matches the mapping against the decoded path, so a percent-encoded route (e.g. GET /api/%6cogs, /api/%61ssets)
        // reaches RecentLogsController / the asset enumeration while a raw-URI equals() below would miss it - letting a
        // repository-scoped key evade the deployment-wide "*" rebind (/api/logs, /api/consistency, /api/posture) or the
        // /api/assets ?repo re-scope and read another scope's content. Decode, then reject an un-normalized URI (an empty
        // "//" or dot "/./"/"/.." segment, incl. a %2f/%2e that decodes into one) outright - never a legitimate artifact,
        // /api or /actuator route - so it cannot slip a deployment-wide read past these equals() checks either.
        String uri = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        if (!normalized(uri)) {
            request.setAttribute("jenesis.repository.decision", Authorization.Decision.FORBIDDEN);
            return new AuthorizationDecision(false);
        }
        String scope = request.getHeader("Jenesis-Repository-Name");
        // The asset enumeration scopes the store it reads by the ?repo= parameter, not the routed name, so authorize
        // the repository that is actually enumerated - otherwise a key scoped to repository A could satisfy the header
        // check for A and then read repository B by passing repo=B. Read the parameter only for that GET route (never
        // on an upload path, where touching getParameter could drain a form-encoded body). When repo is absent the
        // controller falls back to the routed name, which is exactly this header, so the scopes stay in lock-step.
        if ("/api/assets".equals(uri)) {
            String repo = request.getParameter("repo");
            if (repo != null && !repo.isBlank()) {
                scope = repo;
            }
        }
        // GET /api/logs, GET /api/consistency, GET /api/posture and the /actuator endpoints serve DEPLOYMENT-WIDE
        // content - every repository's / every tenant's log lines (logger names + messages carrying other scopes'
        // coordinates, paths, errors), the whole fleet's per-node consistency state, every tenant's unsafe-setting
        // advisories (each posture row names the tenant, scope and the exact jenesis.* key/value that is unsafe - the
        // deployment's whole security-weakness enumeration, though never a resolved secret value), and the actuator's
        // deployment-wide Micrometer metrics (request counts/URIs/statuses across all repositories, JVM internals) and
        // build info. Authorizing them against the caller's self-named Jenesis-Repository-Name lets a key scoped to a
        // single repository read every other scope's content by naming its own repository (a cross-scope leak, the same
        // class the /api/assets ?repo re-scope closes). Bind them to the deployment-wide scope "*" instead, so only a key
        // holding a wildcard (deployment-wide) grant may read them - a repository-scoped key is refused. A "*" grant
        // still reads the whole view (the intended deployment-observability feature); a per-repo grant no longer does.
        // (/actuator/health is permit-all in the security chain, so it never reaches this manager; binding the /actuator
        // subtree to "*" here covers /actuator/metrics, /actuator/info and any other exposed actuator endpoint.)
        // The deployment-wide OPERATOR-observability routes: GET/HEAD /api/logs, /api/consistency and the /actuator
        // subtree. /api/posture is deployment-wide too and is bound to "*" alongside them, but it is INTENTIONALLY
        // anonymous-readable (a public advisory, already tested), so it is deliberately kept OUT of this operator set.
        boolean operatorObservability = ("GET".equals(method) || "HEAD".equals(method))
                && ("/api/logs".equals(uri) || "/api/consistency".equals(uri)
                        || "/actuator".equals(uri) || uri.startsWith("/actuator/"));
        if (operatorObservability
                || (("GET".equals(method) || "HEAD".equals(method)) && "/api/posture".equals(uri))) {
            scope = "*";
        }
        String key = request.getHeader("Jenesis-Repository-Key");
        boolean keyless = key == null || key.isBlank();
        // Reuse the router's own resolution of the in-repository path (the request URI with the /repository prefix
        // stripped, exactly as the format dispatcher matches on) rather than re-deriving it here, so a path-scoped
        // grant (<repo>:<prefix>) authorizes exactly the subtree it grants. A repository-wide grant carries no prefix
        // and covers every path, so threading the path changes nothing for it - it only makes a prefix grant, which
        // is otherwise dead against the pathless 3-arg check, actually evaluated.
        String path = routing.route(request).path();
        Authorization.Decision decision;
        try {
            // A key may carry a source-IP allowlist (set-allowed-addresses): a request from an address outside it is
            // forbidden even with an otherwise-valid key, so a stolen key is useless off its network. Enforce it on the
            // request path here - authorize() alone never consults it - deriving the client address the way
            // Authorization.clientAddress documents (the TCP peer, honouring a forwarded header only from a trusted
            // proxy; with no trusted proxies configured a client-set X-Forwarded-For is ignored, so the allowlist
            // cannot be spoofed). A key with no allowlist admits every address, so this is a no-op for the common case.
            if (!authorization.addressAllowed(key, clientAddress(request))) {
                decision = Authorization.Decision.FORBIDDEN;
            } else {
                decision = authorization.authorize(key, scope, path, required);
            }
        } catch (IOException e) {
            decision = Authorization.Decision.FORBIDDEN;
        }
        // Close an anonymous-grant cross-scope disclosure on the deployment-wide operator-observability routes. When an
        // operator enables the public-mirror opt-in jenesis.repository.anonymous-rights=repository:read, the anonymous
        // grant parses to the WILDCARD scope "*" - exactly what these routes are rebound to above - so a completely
        // KEYLESS caller would satisfy covers("*","*",path) + grantedBy("repository:read") and authorize() ALLOWS it,
        // reading the deployment-wide operator view (the fleet log ring, the whole fleet's consistency state, the
        // actuator metrics) with no key at all. That contradicts the intent stated above: only a key holding a wildcard
        // grant may read them. Refuse the keyless caller here - downgrade that anonymous-grant ALLOWED to FORBIDDEN, so
        // the anonymous wildcard grant can no longer satisfy an operator route. Scoped to keyless + ALLOWED, so every
        // other outcome is untouched: a keyless request on an enforcing deployment WITHOUT the anonymous role is already
        // UNAUTHORIZED (a 401, not ALLOWED) and is left exactly as-is; a present wildcard KEY still reads them; a
        // repository-scoped key is still refused via covers; and the anonymous artifact GET and the intentionally-
        // anonymous /api/posture advisory (outside operatorObservability) are unchanged.
        if (operatorObservability && keyless && decision == Authorization.Decision.ALLOWED) {
            decision = Authorization.Decision.FORBIDDEN;
        }
        request.setAttribute("jenesis.repository.decision", decision);
        return new AuthorizationDecision(decision == Authorization.Decision.ALLOWED);
    }

    /** The client's source address for the allowlist check: the TCP peer, with a forwarded header honoured only from a
     *  trusted proxy. No trusted proxies are configured on the free single-token server, so the peer is always the
     *  client and a client-supplied {@code X-Forwarded-For} is ignored (it cannot spoof the allowlist). A deployment
     *  that terminates behind a real proxy contributes a richer manager that passes its trusted-proxy CIDRs here. */
    private static String clientAddress(HttpServletRequest request) {
        return Authorization.clientAddress(
                request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), List.of());
    }

    /** Whether the (already percent-decoded) request path is normalized - carries no empty ({@code //}) or dot
     *  ({@code /.}, {@code /..}) segment. Spring routes on the normalized path, so an un-normalized URI would reach a
     *  controller while the equals()-based scope rebinds above misread it; a legitimate artifact, {@code /api} or
     *  {@code /actuator} route never carries such a segment, so a request that does is rejected rather than classified.
     *  A trailing single slash is left alone - it does not shift an equals() match. Public for a direct unit test. */
    public static boolean normalized(String path) {
        return !path.contains("//")
                && !path.contains("/./") && !path.contains("/../")
                && !path.endsWith("/.") && !path.endsWith("/..");
    }
}
