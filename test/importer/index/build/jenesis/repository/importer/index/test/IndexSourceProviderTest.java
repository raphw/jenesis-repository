package build.jenesis.repository.importer.index.test;

import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.index.IndexSourceProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider contract: answers to {@code index}, requires the ecosystem format up front, resolves it among the
 * installed formats (this test module provides the fakes), refuses an absent or non-proxying format and an
 * unreachable root, wires the walk's root from url and repository, and injects HTTP basic credentials around the
 * fetcher.
 */
class IndexSourceProviderTest {

    private final IndexSourceProvider provider = new IndexSourceProvider();

    @Test
    void the_provider_answers_to_index_and_requires_a_format() {
        assertThat(provider.name()).isEqualTo("index");
        assertThat(provider.label()).isEqualTo("Format index");
        assertThat(provider.handles("index")).isTrue();
        assertThat(provider.handles("nexus")).isFalse();
        assertThat(provider.requiresFormat()).isTrue();
    }

    @Test
    void a_request_without_a_format_builds_no_source() {
        FakeFetcher fetcher = new FakeFetcher().on("http://source.local/", 200, new byte[0]);
        assertThat(provider.create(new ImportRequest(URI.create("http://source.local"), "."), fetcher)).isNull();
    }

    @Test
    void an_absent_format_builds_no_source() {
        FakeFetcher fetcher = new FakeFetcher().on("http://source.local/", 200, new byte[0]);
        ImportRequest request = new ImportRequest(URI.create("http://source.local"), ".").withFormat("missing");
        assertThat(provider.create(request, fetcher)).isNull();
    }

    @Test
    void a_format_that_cannot_proxy_builds_no_source() {
        FakeFetcher fetcher = new FakeFetcher().on("http://source.local/", 200, new byte[0]);
        ImportRequest request = new ImportRequest(URI.create("http://source.local"), ".").withFormat("hosted");
        assertThat(provider.create(request, fetcher)).isNull();
    }

    @Test
    void an_unreachable_root_builds_no_source() {
        ImportRequest request = new ImportRequest(URI.create("http://absent.invalid"), ".").withFormat("fake");
        assertThat(provider.create(request, new FakeFetcher())).isNull();
    }

    @Test
    void the_root_appends_the_repository_and_any_status_counts_as_reachable() {
        FakeFetcher fetcher = new FakeFetcher().on("http://source.local/repo/", 403, new byte[0]);
        ImportRequest request = new ImportRequest(URI.create("http://source.local/"), "/repo/").withFormat("fake");
        assertThat(provider.create(request, fetcher)).isNotNull();
        assertThat(fetcher.urls).containsExactly("http://source.local/repo/");
    }

    @Test
    void credentials_ride_as_basic_auth_on_every_request() throws Exception {
        String token = Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        FakeFetcher fetcher = new FakeFetcher()
                .on("http://source.local/", 200, new byte[0])
                .on("http://source.local/index", 200, "alpha/a.bin http://source.local/a.bin\n"
                        .getBytes(StandardCharsets.UTF_8))
                .on("http://source.local/a.bin", 200, "bytes".getBytes(StandardCharsets.UTF_8));
        ImportRequest request = new ImportRequest(URI.create("http://source.local"), ".")
                .withFormat("fake")
                .withCredentials("user", "secret");
        ImportSource source = provider.create(request, fetcher);
        assertThat(source).isNotNull();
        source.forEach((format, path, content) -> content.open().close(), cursor -> { });
        assertThat(fetcher.headers)
                .allSatisfy(headers -> assertThat(headers).containsEntry("Authorization", "Basic " + token));
    }
}
