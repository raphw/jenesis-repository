package build.jenesis.repository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Maps a denied request to the status the {@link Authorization} credential model intends, rather than letting
 * Spring Security guess it from whether the request looks anonymous. The {@link RepositoryAuthorizationManager}
 * records the {@link Authorization.Decision} it computed on the request; this single component serves as both the
 * {@link AuthenticationEntryPoint} (Spring's path for a denial it deems unauthenticated) and the
 * {@link AccessDeniedHandler} (its path for a denial it deems authenticated), so either path answers {@code 403}
 * for a {@code FORBIDDEN} decision (a key that lacks the right) and {@code 401} otherwise (no key, a malformed or
 * expired key). The decision drives the status, so a present-but-unauthorized key is always a {@code 403}.
 */
public final class RepositoryAuthorizationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        respond(request, response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) {
        respond(request, response);
    }

    private void respond(HttpServletRequest request, HttpServletResponse response) {
        Object decision = request.getAttribute("jenesis.repository.decision");
        response.setStatus(decision == Authorization.Decision.FORBIDDEN ? 403 : 401);
    }
}
