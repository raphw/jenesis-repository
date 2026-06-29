package build.jenesis.repository;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Authorizes a request against the {@link Authorization} credential model. An anonymous deployment (the headless
 * default) allows everything; an enforcing one reads the {@code Jenesis-Repository-Key} and optional
 * {@code Jenesis-Repository-Name} headers and requires {@code repository:read} for a GET/HEAD and
 * {@code repository:write} for any other method. A denied request is mapped by Spring Security to a {@code 403};
 * distinguishing {@code 401} from {@code 403} is not attempted here.
 */
@Component
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
        try {
            Authorization.Decision decision = authorization.authorize(
                    request.getHeader("Jenesis-Repository-Key"),
                    request.getHeader("Jenesis-Repository-Name"),
                    required);
            return new AuthorizationDecision(decision == Authorization.Decision.ALLOWED);
        } catch (IOException e) {
            return new AuthorizationDecision(false);
        }
    }
}
