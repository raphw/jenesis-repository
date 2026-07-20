package build.jenesis.repository.server;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Server-side security for the repository as auto-configuration: stateless, deny-by-default authorization
 * delegated to the {@link RepositoryAuthorizationManager} (a pass-through when the deployment is anonymous), with the
 * Actuator health endpoint left open for liveness/readiness probes. The {@link KeyAuthenticationFilter} runs first to
 * lift the {@code Jenesis-Repository-Key} header into the security context. CSRF, HTTP Basic and form login are
 * disabled - this is a machine-to-machine artifact API keyed by a header, not a browser session. Both the
 * authentication entry point and the access-denied handler are the {@link RepositoryAuthorizationEntryPoint}, so a
 * denied request answers the status the credential model intends ({@code 401} unauthorized, {@code 403} forbidden)
 * whichever Spring Security failure path it takes.
 *
 * <p>The chain is a <em>composition seam</em>, not a fixed chain. The authorization manager, the {@link RateLimitFilter}
 * and the chain itself are {@link ConditionalOnMissingBean conditional}, and every discovered
 * {@link SecurityChainCustomizer} is applied over the baseline before the {@code anyRequest} catch-all is registered.
 * So a deployment that needs a richer authorization manager (multi-tenant scoping, an operator-tenant check, usage
 * recording), extra open routes (a console page, a self-authenticating webhook, an OIDC token endpoint) or an extra
 * filter (a request-body cap) contributes them as beans and rides <em>this</em> chain - reusing the shared
 * {@link KeyAuthenticationFilter}, {@link RateLimitFilter} and {@link Authorization} credential model - rather than
 * excluding this auto-configuration and forking the whole chain.
 */
@AutoConfiguration
@EnableWebSecurity
public class RepositorySecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "repositoryAuthorizationManager")
    public RepositoryAuthorizationManager repositoryAuthorizationManager(Authorization authorization,
                                                                         RepositoryRouting routing) {
        return new RepositoryAuthorizationManager(authorization, routing);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthFailures authFailures() {
        // A registry-free accessor seam: the key entry point (and the console's OIDC/SAML login failure handlers)
        // record denials here, and a metrics layer scrapes them into jenesis.auth.failures.
        return new AuthFailures();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, Authorization authorization,
                                           RepositoryProperties properties) {
        // A bean (not an inline filter) so a metrics layer can scrape the same instance the chain sheds load with.
        return new RateLimitFilter(rateLimiter, authorization, properties.getRateLimit());
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Qualifier("repositoryAuthorizationManager")
                                                   AuthorizationManager<RequestAuthorizationContext> authorizationManager,
                                                   RateLimitFilter rateLimitFilter,
                                                   AuthFailures authFailures,
                                                   ObjectProvider<SecurityChainCustomizer> customizers)
            throws Exception {
        RepositoryAuthorizationEntryPoint entryPoint = new RepositoryAuthorizationEntryPoint(authFailures);
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(entryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new KeyAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        // The composition seam: contributed customizers layer their open routes and filters over the baseline while
        // anyRequest is still unset, so their permit rules keep precedence over the deny-by-default catch-all below.
        customizers.orderedStream().forEach(customizer -> {
            try {
                customizer.customize(http);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to apply a repository security-chain customizer", e);
            }
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().access(authorizationManager));
        return http.build();
    }
}
