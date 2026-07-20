package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proxy upstream ({@code jenesis.repository.proxy.<format>=<url>}) is pulled through verbatim - a build tool draws
 * its dependencies over it - so a non-HTTPS upstream is a tamper-in-transit vector. The auto-configuration classifies
 * such an upstream as insecure and warns loudly at boot (the same loud-not-silent stance the anonymous-auth default
 * takes), while still accepting it so a plaintext internal mirror and the documented config shape keep working. This
 * pins the scheme classification that drives that warning.
 */
class ProxyUpstreamSchemeTest {

    @Test
    void an_https_upstream_is_secure() {
        assertThat(RepositoryAutoConfiguration.isInsecureUpstream(URI.create("https://repo1.maven.org/maven2")))
                .isFalse();
        assertThat(RepositoryAutoConfiguration.isInsecureUpstream(URI.create("HTTPS://Repo.Example/")))
                .as("the scheme check is case-insensitive").isFalse();
    }

    @Test
    void a_plaintext_or_schemeless_upstream_is_insecure() {
        assertThat(RepositoryAutoConfiguration.isInsecureUpstream(URI.create("http://mirror.internal/maven2")))
                .as("plaintext http is a tamper-in-transit vector").isTrue();
        assertThat(RepositoryAutoConfiguration.isInsecureUpstream(URI.create("ftp://legacy.example/pub")))
                .as("any non-https transport is insecure").isTrue();
    }
}
