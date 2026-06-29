package build.jenesis.repository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Lifts the {@code Jenesis-Repository-Key} header into the Spring Security context, so a keyed request is seen as
 * authenticated (the key as the principal) rather than anonymous. The actual rights check is the
 * {@link RepositoryAuthorizationManager}, which reads the same header and consults the {@link Authorization} grants;
 * this filter only populates the principal and never rejects, always continuing the chain.
 */
public class KeyAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("Jenesis-Repository-Key");
        if (key != null && !key.isBlank()) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    key, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
