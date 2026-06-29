package build.jenesis.repository.test;

import build.jenesis.repository.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credential model in isolation: keys are {@code <tenant>.<secret>} hashed at rest, a grant maps a scope to
 * surface-named {@code <surface>:<verb>} rights with a {@code *} fallback, and one key can vary its rights across
 * the repository and the cache (or carry either alone, or all of them at once). The verdicts here are exactly what
 * the HTTP layer maps to 200/201, 401 and 403.
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
        authorization.grant("acme.readonly", "*", Authorization.REPOSITORY_READ);
        assertThat(authorization.authorize("acme.readonly", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.readonly", null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void a_write_grant_carries_both_repository_rights() throws IOException {
        authorization.grant("acme.ci", "*", Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE);
        assertThat(authorization.authorize("acme.ci", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.ci", null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void a_grant_is_scoped_to_its_repository_with_no_wildcard_leak() throws IOException {
        authorization.grant("acme.releases", "releases", Authorization.REPOSITORY_WRITE);
        assertThat(authorization.authorize("acme.releases", "releases", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.releases", "snapshots", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void one_key_varies_rights_across_the_repository_and_the_cache() throws IOException {
        authorization.grant("acme.shared", "*",
                Authorization.REPOSITORY_READ, Authorization.CACHE_READ, Authorization.CACHE_WRITE);
        assertThat(authorization.authorize("acme.shared", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.shared", null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
        assertThat(authorization.authorize("acme.shared", null, Authorization.CACHE_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.shared", null, Authorization.CACHE_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void the_grant_token_grammar_confers_exact_per_surface_wildcard_and_nothing_else() throws IOException {
        authorization.grant("acme.surface", "*", "repository:*");
        assertThat(authorization.authorize("acme.surface", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.surface", null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize("acme.surface", null, Authorization.CACHE_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);

        authorization.grant("acme.exact", "*", Authorization.REPOSITORY_READ);
        assertThat(authorization.authorize("acme.exact", null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);

        authorization.grant("acme.bogus", "*", "nonsense");
        assertThat(authorization.authorize("acme.bogus", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void an_all_privileges_grant_covers_every_surface_and_verb() throws IOException {
        authorization.grantAll("acme.admin", "*");
        List<String> rights = List.of(Authorization.CACHE_READ, Authorization.CACHE_WRITE,
                Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE,
                Authorization.MANAGE_READ, Authorization.MANAGE_WRITE);
        for (String right : rights) {
            assertThat(authorization.authorize("acme.admin", "any-repository", right))
                    .as("right %s", right)
                    .isEqualTo(Authorization.Decision.ALLOWED);
        }
    }

    @Test
    void a_missing_key_is_unauthorized_and_an_unknown_key_is_forbidden() throws IOException {
        assertThat(authorization.authorize(null, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.UNAUTHORIZED);
        assertThat(authorization.authorize("nodot", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.UNAUTHORIZED);
        assertThat(authorization.authorize("acme.unknown", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void an_anonymous_repository_allows_everything() throws IOException {
        assertThat(Authorization.anonymous().authorize(null, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void an_expired_key_is_unauthorized_and_a_reset_expiry_restores_it() throws IOException {
        String key = "acme.temp";
        String hash = Authorization.hash(key);
        authorization.provision("acme", hash, "ci token", Instant.now().minusSeconds(60));
        authorization.setGrant("acme", hash, "*", Authorization.REPOSITORY_READ);
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.UNAUTHORIZED);

        authorization.setExpiry("acme", hash, Instant.now().plusSeconds(3600));
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void a_provisioned_credential_lists_reads_records_use_and_revokes_by_hash() throws IOException {
        String key = "acme.deploy";
        String hash = Authorization.hash(key);
        authorization.provision("acme", hash, "deploy key", null);
        authorization.setGrant("acme", hash, "releases", Authorization.REPOSITORY_WRITE);

        assertThat(authorization.credentials("acme")).contains(hash);
        Authorization.Credential credential = authorization.credential("acme", hash).orElseThrow();
        assertThat(credential.label()).isEqualTo("deploy key");
        assertThat(credential.created()).isNotNull();
        assertThat(credential.grants()).containsEntry("releases", Authorization.REPOSITORY_WRITE);

        authorization.recordUsed("acme", hash, Instant.now());
        assertThat(authorization.credential("acme", hash).orElseThrow().lastUsed()).isNotNull();

        authorization.revoke("acme", hash);
        assertThat(authorization.authorize(key, "releases", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
        assertThat(authorization.credential("acme", hash)).isEmpty();
    }
}
