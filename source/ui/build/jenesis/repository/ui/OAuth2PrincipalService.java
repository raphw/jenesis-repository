package build.jenesis.repository.ui;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Maps a GitHub (OAuth2, non-OIDC) sign-in to the console's authorities: it loads the user as usual, then replaces its
 * authorities with the ones {@link Principals} grants the provider-qualified id {@code <registrationId>/<name>}, so a
 * configured admin is an admin and everyone else a reader. The user's attributes and name key are preserved.
 */
public class OAuth2PrincipalService extends DefaultOAuth2UserService {

    private final Principals principals;

    public OAuth2PrincipalService(Principals principals) {
        this.principals = principals;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();
        String nameKey = request.getClientRegistration().getProviderDetails()
                .getUserInfoEndpoint().getUserNameAttributeName();
        String id = registrationId + "/" + user.getAttributes().get(nameKey);
        return new DefaultOAuth2User(principals.authorities(id), user.getAttributes(), nameKey);
    }
}
