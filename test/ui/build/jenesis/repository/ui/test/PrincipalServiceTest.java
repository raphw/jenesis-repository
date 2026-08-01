package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.OAuth2PrincipalService;
import build.jenesis.repository.ui.OidcPrincipalService;
import build.jenesis.repository.ui.Principals;
import build.jenesis.repository.ui.UiProperties;
import module org.junit.jupiter.api;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestOperations;

import java.lang.reflect.Proxy;
import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider-qualified id both principal services construct - the exact string {@link Principals} keys the ADMIN
 * decision on. A GitHub (OAuth2, non-OIDC) sign-in must ask {@code Principals} about {@code github/<login>}; a generic
 * OIDC sign-in about {@code oidc/<sub>}. The user-info fetch is short-circuited (a stub {@code RestOperations} for the
 * OAuth2 path, {@code retrieveUserInfo=false} for the OIDC path) so the test needs no network - only the id derivation
 * and the authority mapping are under test.
 */
class PrincipalServiceTest {

    /** A {@link Principals} that records the id it was asked about, so the test asserts the exact provider-qualified id. */
    private static final class CapturingPrincipals extends Principals {
        private String captured;

        CapturingPrincipals(String admins) {
            super(properties(admins));
        }

        @Override
        public List<GrantedAuthority> authorities(String id) {
            this.captured = id;
            return super.authorities(id);
        }
    }

    @Test
    void a_github_sign_in_is_keyed_on_the_provider_qualified_login() {
        CapturingPrincipals principals = new CapturingPrincipals("github/octocat");
        OAuth2PrincipalService service = new OAuth2PrincipalService(principals);
        service.setRestOperations(userInfo(Map.of("login", "octocat", "id", 1)));

        OAuth2User user = service.loadUser(new OAuth2UserRequest(githubRegistration(), bearer()));

        assertThat(principals.captured).as("the id Principals decides ADMIN on is 'github/<login>'")
                .isEqualTo("github/octocat");
        assertThat(roles(user)).as("that id is the configured admin, so it maps to ADMIN")
                .contains("ROLE_USER", "ROLE_ADMIN");
        assertThat(user.getName()).isEqualTo("octocat");
    }

    @Test
    void a_github_login_that_is_not_the_configured_admin_is_only_a_reader() {
        CapturingPrincipals principals = new CapturingPrincipals("github/someone-else");
        OAuth2PrincipalService service = new OAuth2PrincipalService(principals);
        service.setRestOperations(userInfo(Map.of("login", "octocat", "id", 1)));

        OAuth2User user = service.loadUser(new OAuth2UserRequest(githubRegistration(), bearer()));

        assertThat(principals.captured).isEqualTo("github/octocat");
        assertThat(roles(user)).contains("ROLE_USER").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void an_oidc_sign_in_is_keyed_on_the_provider_qualified_subject() {
        CapturingPrincipals principals = new CapturingPrincipals("oidc/subject-123");
        OidcPrincipalService service = new OidcPrincipalService(principals);
        // No user-info request: the subject comes straight off the id token, so the test needs no network.
        service.setRetrieveUserInfo(request -> false);

        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .subject("subject-123").issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        OidcUser user = service.loadUser(new OidcUserRequest(oidcRegistration(), bearer(), idToken));

        assertThat(principals.captured).as("the id Principals decides ADMIN on is 'oidc/<sub>'")
                .isEqualTo("oidc/subject-123");
        assertThat(roles(user)).as("that id is the configured admin, so it maps to ADMIN")
                .contains("ROLE_USER", "ROLE_ADMIN");
        assertThat(user.getSubject()).isEqualTo("subject-123");
    }

    private static UiProperties properties(String admins) {
        UiProperties properties = new UiProperties();
        properties.setAdmins(admins);
        return properties;
    }

    private static List<String> roles(OAuth2User user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static OAuth2AccessToken bearer() {
        Instant now = Instant.now();
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token", now, now.plusSeconds(3600));
    }

    private static ClientRegistration githubRegistration() {
        return ClientRegistration.withRegistrationId("github")
                .clientId("gh-client").clientSecret("secret")
                .clientName("GitHub")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/github")
                .authorizationUri("http://localhost/authorize")
                .tokenUri("http://localhost/token")
                .userInfoUri("http://localhost/userinfo")
                .userNameAttributeName("login")
                .build();
    }

    private static ClientRegistration oidcRegistration() {
        return ClientRegistration.withRegistrationId("oidc")
                .clientId("oidc-client").clientSecret("secret")
                .clientName("Single sign-on")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("openid")
                .redirectUri("http://localhost/login/oauth2/code/oidc")
                .authorizationUri("http://localhost/authorize")
                .tokenUri("http://localhost/token")
                .userInfoUri("http://localhost/userinfo")
                .userNameAttributeName("sub")
                .jwkSetUri("http://localhost/jwks")
                .build();
    }

    /** A {@link RestOperations} whose only exercised method, {@code exchange(RequestEntity, ParameterizedTypeReference)},
     *  answers the user-info request with the given attribute map - so the OAuth2 user service never touches a network. */
    private static RestOperations userInfo(Map<String, Object> attributes) {
        return (RestOperations) Proxy.newProxyInstance(
                RestOperations.class.getClassLoader(), new Class<?>[]{RestOperations.class},
                (proxy, method, args) -> {
                    if ("exchange".equals(method.getName())) {
                        return ResponseEntity.ok(attributes);
                    }
                    if ("toString".equals(method.getName())) {
                        return "stub-rest-operations";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
