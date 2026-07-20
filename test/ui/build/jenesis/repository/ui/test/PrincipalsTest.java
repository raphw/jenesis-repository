package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.Principals;
import build.jenesis.repository.ui.UiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's authority model is deny-by-default: an unconfigured {@code jenesis.ui.admins} grants {@code ROLE_USER}
 * but never {@code ROLE_ADMIN}, so an unconfigured deployment denies writes (a POST/PUT/DELETE needs {@code ADMIN})
 * rather than handing full admin to whoever signs in. A configured id still becomes an admin, and the {@code *}
 * wildcard is the explicit opt-out that re-opens the console to every authenticated user.
 */
class PrincipalsTest {

    @Test
    void an_empty_admins_list_grants_no_admin_so_writes_are_denied_by_default() {
        List<String> roles = roles(principals(""), "oidc/anyone");
        assertThat(roles).contains("ROLE_USER").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void a_configured_admin_is_granted_admin_and_others_are_not() {
        Principals principals = principals("oidc/alice, github/bob");
        assertThat(roles(principals, "oidc/alice")).contains("ROLE_USER", "ROLE_ADMIN");
        assertThat(roles(principals, "github/bob")).contains("ROLE_ADMIN");
        assertThat(roles(principals, "oidc/mallory")).contains("ROLE_USER").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void the_wildcard_opts_every_authenticated_user_back_into_admin() {
        List<String> roles = roles(principals("*"), "oidc/anyone");
        assertThat(roles).contains("ROLE_USER", "ROLE_ADMIN");
    }

    private static Principals principals(String admins) {
        UiProperties properties = new UiProperties();
        properties.setAdmins(admins);
        return new Principals(properties);
    }

    private static List<String> roles(Principals principals, String id) {
        return principals.authorities(id).stream().map(GrantedAuthority::getAuthority).toList();
    }
}
