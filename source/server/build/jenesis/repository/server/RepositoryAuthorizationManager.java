package build.jenesis.repository.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

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
        String scope = request.getHeader("Jenesis-Repository-Name");
        // The asset enumeration scopes the store it reads by the ?repo= parameter, not the routed name, so authorize
        // the repository that is actually enumerated - otherwise a key scoped to repository A could satisfy the header
        // check for A and then read repository B by passing repo=B. Read the parameter only for that GET route (never
        // on an upload path, where touching getParameter could drain a form-encoded body). When repo is absent the
        // controller falls back to the routed name, which is exactly this header, so the scopes stay in lock-step.
        if ("/api/assets".equals(request.getRequestURI())) {
            String repo = request.getParameter("repo");
            if (repo != null && !repo.isBlank()) {
                scope = repo;
            }
        }
        String key = request.getHeader("Jenesis-Repository-Key");
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
}
