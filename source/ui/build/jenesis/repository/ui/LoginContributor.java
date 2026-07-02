package build.jenesis.repository.ui;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * The seam a login mechanism plugs into: {@link SecurityConfig} owns the authorization rules, the entry point and
 * logout, and applies every {@code LoginContributor} bean to the shared {@link HttpSecurity}, so a mechanism (the
 * bundled OAuth2/OIDC via {@link OAuth2ClientConfig}, others an enterprise edition adds later) contributes its own
 * login without being wired into the core chain. With no contributor present, login is disabled and the app still
 * starts, showing a "not configured" notice on {@code /login}.
 */
@FunctionalInterface
public interface LoginContributor {

    void configure(HttpSecurity http) throws Exception;
}
