package build.jenesis.repository.ui;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The sign-in page. It lists the configured OAuth2/OIDC providers (each linking to Spring Security's authorization
 * endpoint) when a {@link ClientRegistrationRepository} is present, and otherwise shows a local username/password form
 * (which the {@code dev} profile's form login processes) or, in production with no provider configured, a "not
 * configured" notice. An already-authenticated visitor is sent to the console.
 */
@Controller
public class LoginController {

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    public LoginController(ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.clientRegistrations = clientRegistrations;
    }

    @GetMapping("/login")
    public String login(Authentication authentication, Model model) {
        if (authenticated(authentication)) {
            return "redirect:/console";
        }
        List<Map<String, String>> registrations = new ArrayList<>();
        if (clientRegistrations.getIfAvailable() instanceof Iterable<?> available) {
            for (Object entry : available) {
                ClientRegistration registration = (ClientRegistration) entry;
                registrations.add(Map.of("id", registration.getRegistrationId(), "name", registration.getClientName()));
            }
        }
        model.addAttribute("registrations", registrations);
        model.addAttribute("oauthConfigured", !registrations.isEmpty());
        return "login";
    }

    private static boolean authenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
