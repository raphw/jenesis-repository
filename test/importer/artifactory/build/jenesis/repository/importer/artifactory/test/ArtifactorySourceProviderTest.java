package build.jenesis.repository.importer.artifactory.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.artifactory.ArtifactorySource;
import build.jenesis.repository.importer.artifactory.ArtifactorySourceProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Artifactory provider answers only to the {@code artifactory} source name and, because an Artifactory repository
 * has a single package type, refuses a request that carries no ecosystem format (returning null) while building an
 * {@link ArtifactorySource} for one that does.
 */
class ArtifactorySourceProviderTest {

    private final URI url = URI.create("https://art.example/");
    private final ProxyFormat.Fetcher fetcher = (uri, headers) -> Optional.empty();

    @Test
    void it_handles_only_the_artifactory_source() {
        ArtifactorySourceProvider provider = new ArtifactorySourceProvider();
        assertThat(provider.handles("artifactory")).isTrue();
        assertThat(provider.handles("nexus")).isFalse();
    }

    @Test
    void it_refuses_a_request_without_a_format_and_builds_a_source_with_one() {
        ArtifactorySourceProvider provider = new ArtifactorySourceProvider();
        assertThat(provider.create(new ImportRequest(url, "libs-release"), fetcher))
                .as("an Artifactory repository needs an ecosystem format").isNull();

        ImportSource source = provider.create(new ImportRequest(url, "libs-release").withFormat("maven"), fetcher);
        assertThat(source).isInstanceOf(ArtifactorySource.class);
    }
}
