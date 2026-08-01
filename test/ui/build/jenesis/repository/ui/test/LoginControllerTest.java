package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.LoginController;
import module org.junit.jupiter.api;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sign-in page controller: it lists the configured OAuth2/OIDC providers (and flags {@code oauthConfigured}) when a
 * {@link ClientRegistrationRepository} is present, and redirects an already-authenticated visitor to the console.
 */
class LoginControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void it_lists_the_configured_oauth_providers_and_flags_oauth_configured() {
        ClientRegistration github = registration("github", "GitHub");
        ClientRegistration oidc = registration("oidc", "Single sign-on");
        LoginController controller = new LoginController(available(new InMemoryClientRegistrationRepository(github, oidc)));

        Model model = new ConcurrentModel();
        String view = controller.login(anonymous(), model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("oauthConfigured")).isEqualTo(true);
        List<Map<String, String>> registrations = (List<Map<String, String>>) model.getAttribute("registrations");
        assertThat(registrations).extracting(entry -> entry.get("id"))
                .containsExactlyInAnyOrder("github", "oidc");
        assertThat(registrations).extracting(entry -> entry.get("name"))
                .containsExactlyInAnyOrder("GitHub", "Single sign-on");
    }

    @Test
    void it_reports_no_provider_when_no_registration_repository_is_present() {
        LoginController controller = new LoginController(absent());

        Model model = new ConcurrentModel();
        String view = controller.login(anonymous(), model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("oauthConfigured")).isEqualTo(false);
        assertThat((List<?>) model.getAttribute("registrations")).isEmpty();
    }

    @Test
    void an_already_authenticated_visitor_is_redirected_to_the_console() {
        LoginController controller = new LoginController(absent());
        Authentication authenticated = new UsernamePasswordAuthenticationToken(
                "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));

        assertThat(controller.login(authenticated, new ConcurrentModel())).isEqualTo("redirect:/console");
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    private static ClientRegistration registration(String id, String name) {
        return ClientRegistration.withRegistrationId(id)
                .clientId(id + "-client")
                .clientSecret("secret")
                .clientName(name)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/" + id)
                .authorizationUri("http://localhost/authorize")
                .tokenUri("http://localhost/token")
                .build();
    }

    private static ObjectProvider<ClientRegistrationRepository> available(ClientRegistrationRepository repository) {
        return new ObjectProvider<>() {
            @Override
            public ClientRegistrationRepository getObject() throws BeansException {
                return repository;
            }

            @Override
            public ClientRegistrationRepository getIfAvailable() throws BeansException {
                return repository;
            }
        };
    }

    private static ObjectProvider<ClientRegistrationRepository> absent() {
        return new ObjectProvider<>() {
            @Override
            public ClientRegistrationRepository getObject() throws BeansException {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                        ClientRegistrationRepository.class);
            }

            @Override
            public ClientRegistrationRepository getIfAvailable() throws BeansException {
                return null;
            }
        };
    }
}
