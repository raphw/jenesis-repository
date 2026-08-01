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

    private static ProxyFormat.Fetched ok(String body) {
        return new ProxyFormat.Fetched(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    /** The listing URL a walk over base {@code https://src.example/} and repository {@code libs} fetches first,
     *  answered with an empty asset page so the walk terminates after carrying the API key on the one listing call. */
    private static final String LIST_URL = "https://src.example/api/assets?repo=libs";

    private static String walkKey(ImportRequest request) throws IOException {
        FakeFetcher fetcher = new FakeFetcher(Map.of(LIST_URL, ok("{\"assets\":[],\"cursor\":null}")));
        new JenesisSourceProvider().create(request, fetcher)
                .forEach((format, path, content) -> { }, cursor -> { });
        assertThat(fetcher.requests).as("the listing was fetched exactly once").hasSize(1);
        return fetcher.requests.get(0).get("Jenesis-Repository-Key");
    }

    @Test
    void a_username_only_request_uses_the_username_as_the_api_key() throws IOException {
        String key = walkKey(new ImportRequest(URI.create("https://src.example/"), "libs")
                .withCredentials("user-as-key", null));
        assertThat(key).as("username is the fallback key when no password is given").isEqualTo("user-as-key");
    }

    @Test
    void a_request_with_both_prefers_the_password_as_the_api_key() throws IOException {
        String key = walkKey(new ImportRequest(URI.create("https://src.example/"), "libs")
                .withCredentials("the-username", "the-password"));
        assertThat(key).as("the password is preferred over the username").isEqualTo("the-password");
    }
}
