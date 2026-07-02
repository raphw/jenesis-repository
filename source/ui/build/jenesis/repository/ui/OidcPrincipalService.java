package build.jenesis.repository.ui;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Maps a generic OpenID Connect sign-in to the console's authorities: it loads the user as usual, then replaces its
 * authorities with the ones {@link Principals} grants the provider-qualified id {@code oidc/<sub>}, so a configured
 * admin is an admin and everyone else a reader. The id token and user info are preserved.
 */
public class OidcPrincipalService extends OidcUserService {

    private final Principals principals;

    public OidcPrincipalService(Principals principals) {
        this.principals = principals;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(request);
        String id = "oidc/" + user.getSubject();
        return new DefaultOidcUser(principals.authorities(id), user.getIdToken(), user.getUserInfo());
    }
}
