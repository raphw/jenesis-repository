package build.jenesis.repository.test;

import build.jenesis.repository.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
    void a_mint_expires_by_default_and_only_skips_expiry_on_an_explicit_opt_out() throws IOException {
        Instant byDefault = authorization.mintExpiry("acme", null, false);
        assertThat(byDefault).as("a credential expires by default")
                .isAfter(Instant.now().plus(Duration.ofDays(89)));
        Instant requested = Instant.now().plus(Duration.ofDays(7));
        assertThat(authorization.mintExpiry("acme", requested, false))
                .as("an explicit expiry is honoured as given").isEqualTo(requested);
        assertThat(authorization.mintExpiry("acme", null, true))
                .as("non-expiring is only ever an explicit opt-out").isNull();

        assertThat(authorization.withDefaultLifetime(Duration.ofDays(30)).mintExpiry("acme", null, false))
                .as("the default lifetime is overridable").isBefore(Instant.now().plus(Duration.ofDays(31)));
    }

    @Test
    void a_tenant_policy_caps_the_lifetime_and_overrides_a_non_expiring_request() throws IOException {
        authorization.setPolicy("acme", Duration.ofDays(7), Duration.ofDays(30));
        Authorization.Policy policy = authorization.policy("acme");
        assertThat(policy.defaultLifetime()).isEqualTo(Duration.ofDays(7));
        assertThat(policy.maxLifetime()).isEqualTo(Duration.ofDays(30));

        assertThat(authorization.mintExpiry("acme", null, false))
                .as("a blank expiry uses the tenant default").isBefore(Instant.now().plus(Duration.ofDays(8)));
        assertThat(authorization.mintExpiry("acme", Instant.now().plus(Duration.ofDays(365)), false))
                .as("a too-distant expiry is pulled back to the ceiling")
                .isBefore(Instant.now().plus(Duration.ofDays(31)));
        assertThat(authorization.mintExpiry("acme", null, true))
                .as("a non-expiring request is capped, not honoured, under a policy")
                .isAfter(Instant.now()).isBefore(Instant.now().plus(Duration.ofDays(31)));

        String key = Authorization.mint("acme");
        String hash = Authorization.hash(key);
        authorization.provision("acme", hash, "k", null);
        authorization.setExpiry("acme", hash, null);
        assertThat(authorization.credential("acme", hash).orElseThrow().expires())
                .as("clearing an expiry under a ceiling pulls it back to the ceiling, never unbounded")
                .isNotNull().isBefore(Instant.now().plus(Duration.ofDays(31)));
    }

    @Test
    void a_deployment_ceiling_bounds_every_tenant_and_a_tenant_can_only_narrow_it() throws IOException {
        Authorization capped = authorization.withMaxLifetime(Duration.ofDays(60));
        assertThat(capped.policy("acme").maxLifetime())
                .as("the deployment ceiling applies with no tenant policy").isEqualTo(Duration.ofDays(60));
        capped.setPolicy("acme", null, Duration.ofDays(365));
        assertThat(capped.policy("acme").maxLifetime())
                .as("a tenant cannot raise its ceiling past the deployment ceiling").isEqualTo(Duration.ofDays(60));
        capped.setPolicy("acme", null, Duration.ofDays(10));
        assertThat(capped.policy("acme").maxLifetime())
                .as("a tenant can narrow its ceiling further").isEqualTo(Duration.ofDays(10));
    }

    @Test
    void a_leaked_key_revokes_only_itself_and_only_when_well_formed_and_provisioned() throws IOException {
        String key = Authorization.mint("acme");
        authorization.grant(key, "*", Authorization.REPOSITORY_READ);
        assertThat(authorization.revokeLeaked("not-a-jenesis-key"))
                .as("a malformed report revokes nothing").isFalse();
        assertThat(authorization.revokeLeaked(Authorization.mint("acme")))
                .as("a well-formed but unknown key revokes nothing").isFalse();
        assertThat(authorization.revokeLeaked(key)).as("a provisioned leaked key is revoked").isTrue();
        assertThat(authorization.authorize(key, null, Authorization.REPOSITORY_READ))
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
