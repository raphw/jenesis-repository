package build.jenesis.repository.ui;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single-tenant authority model: every signed-in user is a {@code USER}; a user whose provider-qualified id
 * ({@code github/<id>}, {@code oidc/<sub>}) is in the configured {@code jenesis.ui.admins} list is also an
 * {@code ADMIN}. The secure default is deny: when no admins are configured, no one is an {@code ADMIN}, so an
 * unconfigured deployment denies writes (a POST/PUT/DELETE needs {@code ROLE_ADMIN}) rather than silently granting
 * full admin to whoever signs in - matching the enterprise console. Opening the console to every authenticated user
 * (the old single-tenant convenience) is an <em>explicit opt-out</em>: list {@code *} in {@code jenesis.ui.admins}.
 * A deployment that needs a richer tenant-and-role membership model replaces this by contributing its own bean; the
 * seam is the same. This deliberately stays single-tenant and carries no multi-tenant machinery.
 */
public class Principals {

    /** The wildcard admin id: an explicit opt-out that grants {@code ADMIN} to every authenticated user, restoring
     *  the old open single-tenant behaviour without reintroducing the empty-list-grants-everyone footgun. */
    private static final String EVERYONE = "*";

    private final Set<String> admins;

    public Principals(UiProperties properties) {
        this.admins = Arrays.stream(properties.getAdmins().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The authorities granted to the user with this provider-qualified id. */
    public List<GrantedAuthority> authorities(String id) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        // Deny by default: ADMIN only for a configured id (or when the deployment explicitly opts every user in with
        // the * wildcard). An empty admins list therefore grants no ADMIN, so an unconfigured console cannot be
        // written to by an arbitrary sign-in.
        if (admins.contains(EVERYONE) || admins.contains(id)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return authorities;
    }
}
