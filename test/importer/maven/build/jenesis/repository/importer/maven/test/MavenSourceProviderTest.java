package build.jenesis.repository.importer.maven.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.maven.MavenSource;
import build.jenesis.repository.importer.maven.MavenSourceProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generic Maven provider answers only to the {@code maven} source name, needs no up-front format (every asset of
 * a Maven tree is one), and probes the root at creation: an answering host builds a {@link MavenSource} whatever the
 * status - a 403 root still falls back to the index - while a host that does not answer at all builds none, so the
 * server rejects the submission as a bad request instead of failing it asynchronously.
 */
class MavenSourceProviderTest {

    private final ImportRequest request = new ImportRequest(URI.create("https://repo.example/"), "releases");

    @Test
    void it_handles_only_the_maven_source() {
        MavenSourceProvider provider = new MavenSourceProvider();
        assertThat(provider.name()).isEqualTo("maven");
        assertThat(provider.handles("maven")).isTrue();
        assertThat(provider.handles("nexus")).isFalse();
        assertThat(provider.requiresFormat()).isFalse();
    }

    @Test
    void it_builds_a_maven_source_for_a_request_whose_root_answers() {
        ImportSource source = new MavenSourceProvider().create(request, (url, headers) -> {
            assertThat(url).isEqualTo(URI.create("https://repo.example/releases/"));
            return Optional.of(new ProxyFormat.Fetched(403, new byte[0], Map.of()));
        });
        assertThat(source).isInstanceOf(MavenSource.class);
    }

    @Test
    void an_unreachable_root_builds_no_source() {
        assertThat(new MavenSourceProvider().create(request, (url, headers) -> Optional.empty())).isNull();
    }
}
