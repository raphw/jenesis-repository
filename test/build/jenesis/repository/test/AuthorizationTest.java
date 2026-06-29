package build.jenesis.repository.test;

import build.jenesis.repository.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Right;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credential model in isolation: keys are {@code <tenant>.<secret>} hashed at rest, a grant maps a scope to
 * surface-named {@link Right rights} with a {@code *} fallback, and one key can vary its rights across the
 * repository and the cache (or carry either alone, or all of them at once) - the refinement that lets a single
 * credential is honoured. The verdicts here are exactly what the HTTP layer maps to 200/201, 401 and 403.
 */
class AuthorizationTest {

    @TempDir
    Path root;

    private Authorization authorization;

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
    }

    @Test
    void a_read_grant_allows_reads_but_not_writes() throws IOException {
        authorization.grant("acme.readonly", "*", Right.REPOSITORY_READ);
        assertThat(authorization.authorize("acme.readonly", null, Right.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.readonly", null, Right.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void a_write_grant_carries_both_repository_rights() throws IOException {
        authorization.grant("acme.ci", "*", Right.REPOSITORY_READ, Right.REPOSITORY_WRITE);
        assertThat(authorization.authorize("acme.ci", null, Right.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.ci", null, Right.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void a_grant_is_scoped_to_its_repository_with_no_wildcard_leak() throws IOException {
        authorization.grant("acme.releases", "releases", Right.REPOSITORY_WRITE);
        assertThat(authorization.authorize("acme.releases", "releases", Right.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.releases", "snapshots", Right.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void one_key_varies_rights_across_the_repository_and_the_cache() throws IOException {
        authorization.grant("acme.shared", "*", Right.REPOSITORY_READ, Right.CACHE_READ, Right.CACHE_WRITE);
        assertThat(authorization.authorize("acme.shared", null, Right.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.shared", null, Right.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
        assertThat(authorization.authorize("acme.shared", null, Right.CACHE_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.shared", null, Right.CACHE_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void an_all_privileges_grant_covers_every_surface_and_verb() throws IOException {
        authorization.grantAll("acme.admin", "*");
        for (Right right : Right.values()) {
            assertThat(authorization.authorize("acme.admin", "any-repository", right))
                    .as("right %s", right)
                    .isEqualTo(Authorization.Decision.ALLOWED);
        }
    }

    @Test
    void a_missing_key_is_unauthorized_and_an_unknown_key_is_forbidden() throws IOException {
        assertThat(authorization.authorize(null, null, Right.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.UNAUTHORIZED);
        assertThat(authorization.authorize("nodot", null, Right.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.UNAUTHORIZED);
        assertThat(authorization.authorize("acme.unknown", null, Right.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void an_anonymous_repository_allows_everything() throws IOException {
        assertThat(Authorization.anonymous().authorize(null, null, Right.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }
}
