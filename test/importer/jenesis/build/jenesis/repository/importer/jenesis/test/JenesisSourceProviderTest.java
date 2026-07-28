package build.jenesis.repository.importer.jenesis.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.jenesis.JenesisSource;
import build.jenesis.repository.importer.jenesis.JenesisSourceProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jenesis provider answers only to the {@code jenesis} source name, reports a format per asset (so it needs no
 * up-front format), and builds a {@link JenesisSource} from a request.
 */
class JenesisSourceProviderTest {

    private final ProxyFormat.Fetcher fetcher = (url, headers) -> Optional.empty();

    @Test
    void it_handles_only_the_jenesis_source() {
        JenesisSourceProvider provider = new JenesisSourceProvider();
        assertThat(provider.handles("jenesis")).isTrue();
        assertThat(provider.handles("nexus")).isFalse();
        assertThat(provider.requiresFormat()).isFalse();
    }

    @Test
    void it_builds_a_jenesis_source_for_a_request() {
        ImportSource source = new JenesisSourceProvider()
                .create(new ImportRequest(URI.create("https://src.example/"), "libs"), fetcher);
        assertThat(source).isInstanceOf(JenesisSource.class);
    }
}
