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
 * The credential model in isolation: keys are minted as {@code jenk_<tenant>.<secret><checksum>} and hashed at
 * rest, a grant maps a scope to surface-named {@code <surface>:<verb>} rights with a {@code *} fallback, and one
 * key can vary its rights across the repository and the cache (or carry either alone, or all of them at once). A
 * malformed or wrong-checksum key is rejected before any lookup. The verdicts here are exactly what the HTTP layer
 * maps to 200/201, 401 and 403.
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
        String key = Authorization.mint("acme");
        authorization.grant(key, "*", Authorization.REPOSITORY_READ);
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void a_write_grant_carries_both_repository_rights() throws IOException {
        String key = Authorization.mint("acme");
        authorization.grant(key, "*", Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE);
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void a_grant_is_scoped_to_its_repository_with_no_wildcard_leak() throws IOException {
        String key = Authorization.mint("acme");
        authorization.grant(key, "releases", Authorization.REPOSITORY_WRITE);
        assertThat(authorization.authorize(key, "releases", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(key, "snapshots", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void one_key_varies_rights_across_the_repository_and_the_cache() throws IOException {
        String key = Authorization.mint("acme");
        authorization.grant(key, "*",
                Authorization.REPOSITORY_READ, Authorization.CACHE_READ, Authorization.CACHE_WRITE);
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
        assertThat(authorization.authorize(key, null, Authorization.CACHE_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(key, null, Authorization.CACHE_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void the_grant_token_grammar_confers_exact_per_surface_wildcard_and_nothing_else() throws IOException {
        String surface = Authorization.mint("acme");
        authorization.grant(surface, "*", "repository:*");
        assertThat(authorization.authorize(surface, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(surface, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(surface, null, Authorization.CACHE_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);

        String exact = Authorization.mint("acme");
        authorization.grant(exact, "*", Authorization.REPOSITORY_READ);
        assertThat(authorization.authorize(exact, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.FORBIDDEN);

        String bogus = Authorization.mint("acme");
        authorization.grant(bogus, "*", "nonsense");
        assertThat(authorization.authorize(bogus, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void an_all_privileges_grant_covers_every_surface_and_verb() throws IOException {
        String key = Authorization.mint("acme");
        authorization.grantAll(key, "*");
        List<String> rights = List.of(Authorization.CACHE_READ, Authorization.CACHE_WRITE,
                Authorization.REPOSITORY_READ, Authorization.REPOSITORY_WRITE,
                Authorization.MANAGE_READ, Authorization.MANAGE_WRITE);
        for (String right : rights) {
            assertThat(authorization.authorize(key, "any-repository", right))
                    .as("right %s", right)
                    .isEqualTo(Authorization.Decision.ALLOWED);
        }
    }

    @Test
    void a_malformed_or_wrong_checksum_key_is_unauthorized_and_an_unknown_one_is_forbidden() throws IOException {
        assertThat(authorization.authorize(null, null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.UNAUTHORIZED);
        assertThat(authorization.authorize("not-a-jenesis-key", null, Authorization.REPOSITORY_READ))
                .isEqualTo(Authorization.Decision.UNAUTHORIZED);

        String key = Authorization.mint("acme");
        String tampered = key.substring(0, key.length() - 1) + (key.endsWith("A") ? "B" : "A");
        assertThat(authorization.authorize(tampered, null, Authorization.REPOSITORY_READ))
                .as("a flipped checksum is rejected before any lookup")
                .isEqualTo(Authorization.Decision.UNAUTHORIZED);

        assertThat(authorization.authorize(Authorization.mint("acme"), null, Authorization.REPOSITORY_READ))
                .as("a well-formed but never-provisioned key is forbidden, not unauthorized")
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void an_anonymous_repository_allows_everything() throws IOException {
        assertThat(Authorization.anonymous().authorize(null, null, Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void an_expired_key_is_unauthorized_and_a_reset_expiry_restores_it() throws IOException {
        String key = Authorization.mint("acme");
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
        String key = Authorization.mint("acme");
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
