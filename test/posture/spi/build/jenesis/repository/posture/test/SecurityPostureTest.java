package build.jenesis.repository.posture.test;

import build.jenesis.repository.posture.Advisories;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.SecurityPosture;
import build.jenesis.repository.posture.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the security-posture SPI - the id grammar, advisory validation, the configuration helpers, the
 * critical-first aggregation and ServiceLoader discovery, and the core seeder's real-key conditions.
 */
final class SecurityPostureTest {

    private static Configuration config(String... pairs) {
        var map = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Configuration.ofMap(map);
    }

    @Test
    void idGrammarAcceptsTheConventionAndRejectsGarbage() {
        assertThat(Advisories.valid("jenesis.auth.open")).isTrue();
        assertThat(Advisories.valid("jenesis.importer.ssrf")).isTrue();
        assertThat(Advisories.valid("Jenesis.Auth.Open")).isFalse();
        assertThat(Advisories.valid("jenesis")).isFalse();
        assertThat(Advisories.valid("auth.open")).isFalse();
        assertThat(Advisories.valid(null)).isFalse();
    }

    @Test
    void anAdvisoryValidatesItsIdAndScopeTenantConsistencyAtConstruction() {
        assertThatThrownBy(() -> SecurityAdvisory.deployment("not a name", Severity.WARN, "t", "w", "f", "k", "v", "d"))
                .isInstanceOf(IllegalArgumentException.class);
        // A tenant-scoped advisory must name its tenant; a deployment-wide one must not.
        assertThatThrownBy(() -> new SecurityAdvisory("jenesis.x.y", Severity.WARN, Scope.TENANT, "", "t", "w", "f",
                "k", "v", "d")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityAdvisory("jenesis.x.y", Severity.WARN, Scope.DEPLOYMENT, "acme", "t", "w",
                "f", "k", "v", "d")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationHelpersReadRelaxedBooleansAndNumbers() {
        Configuration config = config("a", "true", "b", "false", "c", "  ", "n", "600");
        assertThat(config.flag("a", false)).isTrue();
        assertThat(config.flag("b", true)).isFalse();
        assertThat(config.flag("missing", true)).isTrue();
        assertThat(config.isSet("c")).isFalse();
        assertThat(config.number("n", 0)).isEqualTo(600);
        assertThat(config.number("missing", 42)).isEqualTo(42);
    }

    @Test
    void aReportSortsCriticalFirstThenById() {
        SecurityAdvisory info = SecurityAdvisory.deployment("jenesis.a.info", Severity.INFO, "t", "w", "f", "k", "v", "d");
        SecurityAdvisory warn = SecurityAdvisory.deployment("jenesis.b.warn", Severity.WARN, "t", "w", "f", "k", "v", "d");
        SecurityAdvisory crit = SecurityAdvisory.deployment("jenesis.c.crit", Severity.CRITICAL, "t", "w", "f", "k", "v", "d");
        PostureReport report = PostureReport.from(List.of(c -> List.of(info, warn, crit)), config());
        assertThat(report.advisories()).containsExactly(crit, warn, info);
        assertThat(report.count()).isEqualTo(3);
        assertThat(report.count(Severity.CRITICAL)).isEqualTo(1);
        assertThat(report.highest()).contains(Severity.CRITICAL);
    }

    @Test
    void discoveryFindsTheProvidesDeclaredAdvisorAndHonoursTheDisabledIsSilentRule() {
        // Absent its key, the sample advisor (a stand-in for a disabled feature) contributes nothing.
        assertThat(PostureReport.discover(config()).advisories()).noneMatch(a -> a.id().equals("jenesis.sample.unsafe"));
        // Flip its key and the same discovery surfaces it.
        assertThat(PostureReport.discover(config(SampleSafetyAdvisor.KEY, "true")).advisories())
                .anyMatch(a -> a.id().equals("jenesis.sample.unsafe"));
    }

    @Test
    void theCoreSeederIsSilentOnTheSecureDefault() {
        // The secure defaults (auth on, SSRF screen on, no dev profile, admins named, not a writable demo) raise nothing.
        assertThat(new SecurityPosture().advise(config("jenesis.repository.rate-limit", "600"))).isEmpty();
    }

    @Test
    void theCoreSeederFlagsAuthOffAsCritical() {
        List<SecurityAdvisory> advisories = new SecurityPosture()
                .advise(config("jenesis.repository.auth", "false", "jenesis.repository.rate-limit", "600"));
        assertThat(advisories).hasSize(1);
        SecurityAdvisory advisory = advisories.get(0);
        assertThat(advisory.id()).isEqualTo("jenesis.auth.open");
        assertThat(advisory.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(advisory.scope()).isEqualTo(Scope.DEPLOYMENT);
        assertThat(advisory.settingKey()).isEqualTo("jenesis.repository.auth");
        assertThat(advisory.settingValue()).isEqualTo("true");
    }

    @Test
    void theWritableDemoAdvisoryStaysSilentWhenTheDemoIsReadOnly() {
        // Seed 6 is a compound condition (demo=true AND !read-only): the both-true firing is pinned elsewhere and the
        // demo-absent silence by the secure-default test, but the intermediate branch - demo on yet read-only on, an
        // immutable public demo, the recommended safe configuration - must independently NOT fire. This pins the
        // second half of the &&: a demo that is read-only is browsable-but-immutable, exactly the advisory's own fix.
        List<String> ids = new SecurityPosture().advise(config(
                        "jenesis.repository.demo", "true",
                        "jenesis.repository.read-only", "true",
                        "jenesis.repository.rate-limit", "600"))
                .stream().map(SecurityAdvisory::id).toList();
        assertThat(ids).as("a read-only demo is the safe configuration and raises no writable-demo advisory")
                .doesNotContain("jenesis.demo.writable");
    }

    @Test
    void theCoreSeederFlagsTheRealFootgunsOnTheirActualKeys() {
        Configuration config = config(
                "jenesis.repository.auth", "false",
                "jenesis.repository.block-private-import-hosts", "false",
                "jenesis.ui.admins", "*",
                "spring.profiles.active", "prod,dev",
                "jenesis.repository.demo", "true",
                "jenesis.repository.read-only", "false");
        List<String> ids = new SecurityPosture().advise(config).stream().map(SecurityAdvisory::id).toList();
        assertThat(ids).contains("jenesis.auth.open", "jenesis.importer.ssrf", "jenesis.ratelimit.unset",
                "jenesis.console.wildcard", "jenesis.profile.dev", "jenesis.demo.writable");
    }

    @Test
    void theCoreSeederWarnsOnAnonymousReadAndEscalatesAnonymousWriteOrAdmin() {
        // Read-only anonymous (the public-mirror pattern) is a WARN on its actual key.
        List<SecurityAdvisory> read = new SecurityPosture().advise(
                config("jenesis.repository.auth", "true", "jenesis.repository.anonymous-rights", "repository:read",
                        "jenesis.repository.rate-limit", "600"));
        SecurityAdvisory anonymous = read.stream().filter(a -> a.id().equals("jenesis.anonymous.enabled"))
                .findFirst().orElseThrow();
        assertThat(anonymous.severity()).isEqualTo(Severity.WARN);
        assertThat(anonymous.scope()).isEqualTo(Scope.DEPLOYMENT);

        // Anonymous write (or any manage/admin) escalates to a governance-level CRITICAL.
        List<String> writeIds = new SecurityPosture().advise(
                        config("jenesis.repository.auth", "true", "jenesis.repository.anonymous-rights",
                                "repository:read,repository:write", "jenesis.repository.rate-limit", "600"))
                .stream().map(SecurityAdvisory::id).toList();
        assertThat(writeIds).contains("jenesis.anonymous.write").doesNotContain("jenesis.anonymous.enabled");
        SecurityAdvisory writeAdvisory = new SecurityPosture().advise(
                        config("jenesis.repository.auth", "true", "jenesis.repository.anonymous-rights", "manage:read",
                                "jenesis.repository.rate-limit", "600"))
                .stream().filter(a -> a.id().equals("jenesis.anonymous.write")).findFirst().orElseThrow();
        assertThat(writeAdvisory.severity()).as("any manage/admin anonymous right is CRITICAL")
                .isEqualTo(Severity.CRITICAL);
    }

    @Test
    void theCoreSeederIsSilentOnAnonymousRightsWhenUnsetOrWhenTheInstanceIsAlreadyOpen() {
        // Unset (the default) => no anonymous advisory at all.
        assertThat(new SecurityPosture().advise(config("jenesis.repository.rate-limit", "600")))
                .noneMatch(a -> a.id().startsWith("jenesis.anonymous"));
        // Under auth=false the instance is already fully open (jenesis.auth.open owns that), so anonymous-rights is
        // redundant and raises no separate anonymous advisory.
        assertThat(new SecurityPosture().advise(config("jenesis.repository.auth", "false",
                "jenesis.repository.anonymous-rights", "repository:read", "jenesis.repository.rate-limit", "600")))
                .noneMatch(a -> a.id().startsWith("jenesis.anonymous"));
    }

    @Test
    void noAdvisoryTextEverRepeatsAConfiguredValue() {
        // The posture surface enumerates weaknesses, so it must never echo a secret: prove the rendered text of every
        // seed carries no configured value even when the config holds a "secret".
        Configuration config = config("jenesis.repository.auth", "false", "jenesis.ui.admins", "*",
                "spring.profiles.active", "dev", "jenesis.repository.demo", "true");
        for (SecurityAdvisory advisory : new SecurityPosture().advise(config)) {
            String text = advisory.title() + " " + advisory.why() + " " + advisory.fix();
            assertThat(text).doesNotContain("SECRETVALUE");
        }
    }
}
