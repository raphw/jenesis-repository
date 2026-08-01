package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.ConsoleAdvice;
import module org.junit.jupiter.api;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's deployment-wide model attributes, read straight off the {@link org.springframework.core.env.Environment}
 * with no store write: the read-only flag, the strictly-opt-in anonymous-rights banner (gated on {@code
 * jenesis.repository.auth}, blank under {@code auth=false} where the instance is already open), and the security-posture
 * advisory count the header badge shows (collected through {@code PostureReport.discover} over the effective config).
 */
class ConsoleAdviceTest {

    private static ConsoleAdvice advice(Map<String, String> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", new HashMap<String, Object>(properties)));
        return new ConsoleAdvice(environment);
    }

    @Test
    void read_only_reflects_the_flag_and_defaults_off() {
        assertThat(advice(Map.of()).readOnly()).as("unset defaults to not read-only").isFalse();
        assertThat(advice(Map.of("jenesis.repository.read-only", "true")).readOnly()).isTrue();
        assertThat(advice(Map.of("jenesis.repository.read-only", "false")).readOnly()).isFalse();
    }

    @Test
    void anonymous_rights_is_blank_under_auth_false_even_when_a_grant_is_set() {
        // Under auth=false the instance is already fully open, so the anonymous-rights banner is redundant and must not
        // render - the branch returns "" regardless of the configured grant.
        ConsoleAdvice advice = advice(Map.of(
                "jenesis.repository.auth", "false",
                "jenesis.repository.anonymous-rights", "repository:read"));
        assertThat(advice.anonymousRights()).isEmpty();
    }

    @Test
    void anonymous_rights_is_the_trimmed_grant_under_an_enforcing_deployment() {
        // Enforcing (auth defaults true): a configured grant renders, trimmed of the surrounding whitespace.
        assertThat(advice(Map.of("jenesis.repository.anonymous-rights", "  repository:read  ")).anonymousRights())
                .isEqualTo("repository:read");
        // No grant configured under enforcement: no banner.
        assertThat(advice(Map.of()).anonymousRights()).isEmpty();
    }

    @Test
    void posture_count_reflects_the_advisories_the_effective_config_raises() {
        // postureCount collects through PostureReport.discover(Configuration.of(environment::getProperty)) - so it must
        // read the config through the environment. Only the core SecurityPosture seeder is on this module path, so the
        // count is deterministic: a fully-hardened config raises nothing; disabling authorization adds exactly the
        // jenesis.auth.open advisory. A positive delta proves the count is read from the environment, not fabricated.
        int hardened = advice(Map.of(
                "jenesis.repository.auth", "true",
                "jenesis.repository.rate-limit", "600")).postureCount();
        int open = advice(Map.of(
                "jenesis.repository.auth", "false",
                "jenesis.repository.rate-limit", "600")).postureCount();

        assertThat(hardened).as("a hardened deployment raises no posture advisory - no badge").isZero();
        assertThat(open).as("disabling authorization raises exactly one more advisory").isEqualTo(hardened + 1);
    }
}
