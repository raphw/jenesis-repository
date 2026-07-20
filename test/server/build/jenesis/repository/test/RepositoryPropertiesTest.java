package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The secure default of the repository server's credential model: per-credential authorization is enforced out of the
 * box, and anonymous/open mode is honoured only as an explicit opt-out ({@code jenesis.repository.auth=false}, env
 * {@code JENESIS_REPOSITORY_AUTH=false}). Locks the field default so a fresh deployment never boots open silently.
 */
class RepositoryPropertiesTest {

    @Test
    void per_credential_authorization_is_on_by_default_and_anonymous_is_an_explicit_opt_out() {
        assertThat(new RepositoryProperties().isAuth())
                .as("the secure default: a fresh deployment enforces per-credential authorization").isTrue();

        RepositoryProperties open = new RepositoryProperties();
        open.setAuth(false);
        assertThat(open.isAuth())
                .as("anonymous/open is honoured only as an explicit opt-out (jenesis.repository.auth=false)").isFalse();
    }
}
