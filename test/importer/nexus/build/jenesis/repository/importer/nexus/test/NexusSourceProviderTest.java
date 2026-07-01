package build.jenesis.repository.importer.nexus.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.nexus.NexusSource;
import build.jenesis.repository.importer.nexus.NexusSourceProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Nexus provider answers only to the {@code nexus} source name and builds a {@link NexusSource} from a request.
 */
class NexusSourceProviderTest {

    private final ProxyFormat.Fetcher fetcher = (url, headers) -> Optional.empty();

    @Test
    void it_handles_only_the_nexus_source() {
        NexusSourceProvider provider = new NexusSourceProvider();
        assertThat(provider.handles("nexus")).isTrue();
        assertThat(provider.handles("artifactory")).isFalse();
    }

    @Test
    void it_builds_a_nexus_source_for_a_request() {
        ImportSource source = new NexusSourceProvider()
                .create(new ImportRequest(URI.create("https://nexus.example/"), "maven-releases"), fetcher);
        assertThat(source).isInstanceOf(NexusSource.class);
    }
}
