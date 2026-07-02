package build.jenesis.repository.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Authorizes a request against the {@link Authorization} credential model. An anonymous deployment (the headless
 * default) allows everything; an enforcing one reads the {@code Jenesis-Repository-Key} and optional
 * {@code Jenesis-Repository-Name} headers and requires {@code repository:read} for a GET/HEAD and
 * {@code repository:write} for any other method. The computed {@link Authorization.Decision} is recorded on the
 * request so {@link RepositoryAuthorizationEntryPoint} can answer {@code 401} for an unauthorized request (no key,
 * a malformed or expired key) and {@code 403} for a forbidden one (a key that lacks the right), regardless of which
 * Spring Security failure path the denial takes. It is contributed as a bean by
 * {@link RepositorySecurityAutoConfiguration}.
 */
public class RepositoryAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final Authorization authorization;

    public RepositoryAuthorizationManager(Authorization authorization) {
        this.authorization = authorization;
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
        Authorization.Decision decision;
        try {
            decision = authorization.authorize(
                    request.getHeader("Jenesis-Repository-Key"),
                    request.getHeader("Jenesis-Repository-Name"),
                    required);
        } catch (IOException e) {
            decision = Authorization.Decision.FORBIDDEN;
        }
        request.setAttribute("jenesis.repository.decision", decision);
        return new AuthorizationDecision(decision == Authorization.Decision.ALLOWED);
    }
}
