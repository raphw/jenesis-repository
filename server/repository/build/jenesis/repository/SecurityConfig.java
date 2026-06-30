package build.jenesis.repository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Server-side security for the free repository: stateless, deny-by-default authorization delegated to the
 * {@link RepositoryAuthorizationManager} (which is a pass-through when the deployment is anonymous), with the
 * Actuator health endpoint left open for liveness/readiness probes. The {@link KeyAuthenticationFilter} runs first
 * to lift the {@code Jenesis-Repository-Key} header into the security context. CSRF, HTTP Basic and form login are
 * disabled - this is a machine-to-machine artifact API keyed by a header, not a browser session. Both the
 * authentication entry point and the access-denied handler are the {@link RepositoryAuthorizationEntryPoint}, so a
 * denied request answers the status the credential model intends ({@code 401} unauthorized, {@code 403} forbidden)
 * whichever Spring Security failure path it takes.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RepositoryAuthorizationManager authorizationManager,
                                                   RateLimiter rateLimiter,
                                                   Authorization authorization,
                                                   RepositoryProperties properties)
            throws Exception {
        RepositoryAuthorizationEntryPoint entryPoint = new RepositoryAuthorizationEntryPoint();
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(entryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().access(authorizationManager))
                .addFilterBefore(new RateLimitFilter(rateLimiter, authorization, properties.getRateLimit()),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new KeyAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
