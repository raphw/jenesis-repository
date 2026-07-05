package build.jenesis.repository.server;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * A contribution to the repository's Spring Security filter chain, applied by
 * {@link RepositorySecurityAutoConfiguration} after its baseline (key authentication, rate limiting and the
 * deny-by-default authorization manager) and before the {@code anyRequest} catch-all is registered. An embedder
 * contributes one as a bean to open an extra unauthenticated route (a console landing page, a self-authenticating
 * webhook, an OIDC token-exchange endpoint) or add a filter (a request-body cap) over the free chain, without
 * excluding this auto-configuration and forking the whole chain - the composition seam that keeps the free chain
 * the base even when a distribution layers security concerns of its own on top of it. Because every customizer runs
 * before {@code anyRequest}, a {@code requestMatchers(...).permitAll()} it registers keeps its precedence over the
 * catch-all. Customizers apply in {@link org.springframework.core.annotation.Order} order; a chain with none
 * installed is the plain free chain.
 */
@FunctionalInterface
public interface SecurityChainCustomizer {

    /** Layer this contribution's permit rules and filters onto {@code http} before the chain is finalized. */
    void customize(HttpSecurity http) throws Exception;
}
