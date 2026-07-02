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
 * ({@code github/<id>}, {@code oidc/<sub>}) is in the configured {@code jenesis.ui.admins} list - or every user when
 * that list is empty - is also an {@code ADMIN} (so the free console is open to whoever signs in unless admins are
 * named). An enterprise console replaces this with its own tenant-and-role membership model by contributing its own
 * bean; the seam is the same. This deliberately does not carry the enterprise multi-tenant machinery.
 */
public class Principals {

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
        if (admins.isEmpty() || admins.contains(id)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return authorities;
    }
}
