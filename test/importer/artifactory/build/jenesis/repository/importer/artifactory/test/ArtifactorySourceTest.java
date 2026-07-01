package build.jenesis.repository.importer.artifactory.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.artifactory.ArtifactorySource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Artifactory source walked against a canned storage listing: it lists a repository's files, skips folder entries,
 * reports the repository's supplied format for every asset with a single terminal checkpoint, opens a download lazily,
 * sends basic credentials as an {@code Authorization} header, and raises an {@code IOException} on a failed listing.
 */
class ArtifactorySourceTest {

    private final URI base = URI.create("https://art.example/");
    private final String repository = "libs-release";
    private final String format = "maven";
    private final String listUrl = "https://art.example/api/storage/libs-release?list&deep=1&listFolders=0";
    private final String downloadUrl = "https://art.example/libs-release/org/example/lib-1.0.jar";

    private static ProxyFormat.Fetched ok(String body) {
        return new ProxyFormat.Fetched(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    @Test
    void it_lists_files_skips_folders_and_reports_the_repository_format() throws IOException {
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        String listing = "{\"files\":[{\"uri\":\"/org/example/lib-1.0.jar\",\"folder\":false},"
                + "{\"uri\":\"/subdir\",\"folder\":true},{\"uri\":\"/notes.txt\"}]}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(listing),
                downloadUrl, new ProxyFormat.Fetched(200, jar, Map.of()),
                "https://art.example/libs-release/notes.txt", new ProxyFormat.Fetched(200, new byte[]{2}, Map.of())));

        List<String> formats = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        List<byte[]> downloaded = new ArrayList<>();
        new ArtifactorySource(base, repository, format, fetcher).forEach((assetFormat, path, content) -> {
            formats.add(assetFormat);
            paths.add(path);
            if (path.endsWith("lib-1.0.jar")) {
                try (InputStream in = content.open()) {
                    downloaded.add(in.readAllBytes());
                }
            }
        }, cursors::add);

        assertThat(formats).containsExactly("maven", "maven");
        assertThat(paths).as("the folder entry is skipped").containsExactly("org/example/lib-1.0.jar", "notes.txt");
        assertThat(cursors).as("a single-response listing has one terminal checkpoint").containsExactly((String) null);
        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0)).isEqualTo(jar);
    }

    @Test
    void credentials_are_sent_as_a_basic_authorization_header() throws IOException {
        FakeFetcher fetcher = new FakeFetcher(Map.of(listUrl, ok("{\"files\":[]}")));
        new ArtifactorySource(base, repository, format, fetcher)
                .withCredentials("user", "secret")
                .forEach((assetFormat, path, content) -> { }, cursor -> { });

        String expected = "Basic "
                + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(fetcher.requests).isNotEmpty()
                .allSatisfy(headers -> assertThat(headers.get("Authorization")).isEqualTo(expected));
    }

    @Test
    void a_failed_listing_is_an_io_exception() {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, new ProxyFormat.Fetched(404, new byte[0], Map.of())));
        ArtifactorySource source = new ArtifactorySource(base, repository, format, fetcher);
        assertThatThrownBy(() -> source.forEach((assetFormat, path, content) -> { }, cursor -> { }))
                .isInstanceOf(IOException.class);
    }
}
