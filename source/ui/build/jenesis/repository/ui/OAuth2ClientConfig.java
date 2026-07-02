package build.jenesis.repository.ui;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the OAuth2/OIDC client registrations from configuration: the built-in GitHub provider when
 * {@code jenesis.ui.github.client-id} is set, and a generic OpenID Connect provider - its endpoints and JWK set
 * discovered from {@code jenesis.ui.oidc.issuer-uri} - when that issuer and a client id are set (so any OIDC identity
 * provider, e.g. Google, Keycloak, Okta, Azure AD, works). Every bean here exists only when at least one provider is
 * configured, so the app still starts with login disabled ({@link SecurityConfig} shows a notice) rather than failing,
 * and Spring Boot's property auto-configuration, which rejects a blank client id, is avoided. Discovery makes a network
 * call to the issuer at startup. The login is contributed to the core chain as a {@link LoginContributor}, mapping the
 * signed-in user to authorities through {@link Principals}.
 */
@Configuration(proxyBeanMethods = false)
public class OAuth2ClientConfig {

    /** True when GitHub or OIDC is configured (a non-blank client id, and for OIDC an issuer too). */
    public static class AnyProviderConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return configured(context, "jenesis.ui.github.client-id")
                    || (configured(context, "jenesis.ui.oidc.issuer-uri")
                    && configured(context, "jenesis.ui.oidc.client-id"));
        }

        private static boolean configured(ConditionContext context, String key) {
            String value = context.getEnvironment().getProperty(key, "");
            return value != null && !value.isBlank();
        }
    }

    @Bean
    @Conditional(AnyProviderConfigured.class)
    public OAuth2PrincipalService oauth2PrincipalService(Principals principals) {
        return new OAuth2PrincipalService(principals);
    }

    @Bean
    @Conditional(AnyProviderConfigured.class)
    public OidcPrincipalService oidcPrincipalService(Principals principals) {
        return new OidcPrincipalService(principals);
    }

    /** The OIDC/GitHub login, contributed to the core chain when a provider is configured. */
    @Bean
    @Conditional(AnyProviderConfigured.class)
    public LoginContributor oauth2LoginContributor(OAuth2PrincipalService oauth2Users, OidcPrincipalService oidcUsers) {
        return http -> http.oauth2Login(oauth -> oauth
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo
                        .userService(oauth2Users)
                        .oidcUserService(oidcUsers))
                .defaultSuccessUrl("/console", true));
    }

    @Bean
    @Conditional(AnyProviderConfigured.class)
    public ClientRegistrationRepository clientRegistrationRepository(UiProperties properties) {
        List<ClientRegistration> registrations = new ArrayList<>();
        UiProperties.Github github = properties.getGithub();
        if (!github.getClientId().isBlank()) {
            registrations.add(CommonOAuth2Provider.GITHUB
                    .getBuilder("github")
                    .clientId(github.getClientId().trim())
                    .clientSecret(github.getClientSecret().trim())
                    .scope("read:user")
                    .build());
        }
        UiProperties.Oidc oidc = properties.getOidc();
        if (!oidc.getIssuerUri().isBlank() && !oidc.getClientId().isBlank()) {
            registrations.add(ClientRegistrations.fromIssuerLocation(oidc.getIssuerUri().trim())
                    .registrationId("oidc")
                    .clientId(oidc.getClientId().trim())
                    .clientSecret(oidc.getClientSecret().trim())
                    .clientName(oidc.getName().trim())
                    .build());
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }
}
