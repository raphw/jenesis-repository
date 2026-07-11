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
import java.util.LinkedHashMap;
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

    @Test
    void it_falls_back_to_a_folder_crawl_when_the_file_list_api_is_pro_gated() throws IOException {
        // A free (OSS) Artifactory refuses the deep File List API with this exact message; the walk must then recurse
        // the per-folder Folder Info API for the same files.
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
        String proGated = "{\"errors\":[{\"status\":400,\"message\":"
                + "\"This REST API is available only in Artifactory Pro (see: jfrog.com/artifactory/features).\"}]}";
        String storage = "https://art.example/api/storage/libs-release";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, new ProxyFormat.Fetched(400, proGated.getBytes(StandardCharsets.UTF_8), Map.of()),
                storage, ok("{\"children\":[{\"uri\":\"/org\",\"folder\":true}]}"),
                storage + "/org", ok("{\"children\":[{\"uri\":\"/example\",\"folder\":true}]}"),
                storage + "/org/example", ok("{\"children\":[{\"uri\":\"/lib-1.0.jar\",\"folder\":false},"
                        + "{\"uri\":\"/lib-1.0.pom\",\"folder\":false}]}"),
                "https://art.example/libs-release/org/example/lib-1.0.jar",
                new ProxyFormat.Fetched(200, jar, Map.of()),
                "https://art.example/libs-release/org/example/lib-1.0.pom",
                new ProxyFormat.Fetched(200, pom, Map.of())));

        List<String> formats = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        Map<String, byte[]> downloaded = new LinkedHashMap<>();
        new ArtifactorySource(base, repository, format, fetcher).forEach((assetFormat, path, content) -> {
            formats.add(assetFormat);
            paths.add(path);
            try (InputStream in = content.open()) {
                downloaded.put(path, in.readAllBytes());
            }
        }, cursors::add);

        assertThat(paths).as("the crawl recurses folders and emits every file")
                .containsExactly("org/example/lib-1.0.jar", "org/example/lib-1.0.pom");
        assertThat(formats).as("the repository format is reported for each asset").containsExactly("maven", "maven");
        assertThat(cursors).as("a checkpoint after the top-level subtree, then the terminal null")
                .containsExactly("org", null);
        assertThat(downloaded.get("org/example/lib-1.0.jar")).isEqualTo(jar);
        assertThat(downloaded.get("org/example/lib-1.0.pom")).isEqualTo(pom);
    }

    @Test
    void the_folder_crawl_resumes_after_the_last_reported_top_level_entry() throws IOException {
        byte[] a = "a-jar".getBytes(StandardCharsets.UTF_8);
        byte[] b = "b-jar".getBytes(StandardCharsets.UTF_8);
        String proGated = "{\"errors\":[{\"status\":400,\"message\":"
                + "\"This REST API is available only in Artifactory Pro.\"}]}";
        String storage = "https://art.example/api/storage/libs-release";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, new ProxyFormat.Fetched(400, proGated.getBytes(StandardCharsets.UTF_8), Map.of()),
                storage, ok("{\"children\":[{\"uri\":\"/com\",\"folder\":true},{\"uri\":\"/org\",\"folder\":true}]}"),
                storage + "/com", ok("{\"children\":[{\"uri\":\"/b.jar\",\"folder\":false}]}"),
                storage + "/org", ok("{\"children\":[{\"uri\":\"/a.jar\",\"folder\":false}]}"),
                "https://art.example/libs-release/com/b.jar", new ProxyFormat.Fetched(200, b, Map.of()),
                "https://art.example/libs-release/org/a.jar", new ProxyFormat.Fetched(200, a, Map.of())));

        List<String> paths = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        Map<String, byte[]> downloaded = new LinkedHashMap<>();
        new ArtifactorySource(base, repository, format, fetcher).from("com")
                .forEach((assetFormat, path, content) -> {
                    paths.add(path);
                    try (InputStream in = content.open()) {
                        downloaded.put(path, in.readAllBytes());
                    }
                }, cursors::add);

        assertThat(paths).as("the com/ subtree was completed in a prior run, so only org/ is re-walked")
                .containsExactly("org/a.jar");
        assertThat(cursors).as("one checkpoint for the org subtree, then the terminal null")
                .containsExactly("org", null);
        assertThat(downloaded.get("org/a.jar")).isEqualTo(a);
    }

    @Test
    void a_traversal_laced_listing_path_is_skipped() throws IOException {
        String listing = "{\"files\":[{\"uri\":\"/../../auth/keys\",\"folder\":false},"
                + "{\"uri\":\"/org/example/lib-1.0.jar\",\"folder\":false}]}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(listing),
                downloadUrl, new ProxyFormat.Fetched(200, new byte[]{1}, Map.of())));

        List<String> paths = new ArrayList<>();
        new ArtifactorySource(base, repository, format, fetcher)
                .forEach((assetFormat, path, content) -> paths.add(path), cursor -> { });

        assertThat(paths).as("the hostile path never reaches the consumer")
                .containsExactly("org/example/lib-1.0.jar");
    }

    @Test
    void a_name_uri_refuses_raw_is_percent_encoded_into_the_download_url() throws IOException {
        // An Artifactory name may legally carry a space; splicing it raw into URI.create used to throw an unchecked
        // IllegalArgumentException out of the walk, so the download URL is percent-encoded segment by segment.
        byte[] jar = "spaced-bytes".getBytes(StandardCharsets.UTF_8);
        String listing = "{\"files\":[{\"uri\":\"/org/my lib 1.0.jar\",\"folder\":false}]}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(listing),
                "https://art.example/libs-release/org/my%20lib%201.0.jar",
                new ProxyFormat.Fetched(200, jar, Map.of())));

        List<String> paths = new ArrayList<>();
        List<byte[]> downloaded = new ArrayList<>();
        new ArtifactorySource(base, repository, format, fetcher).forEach((assetFormat, path, content) -> {
            paths.add(path);
            try (InputStream in = content.open()) {
                downloaded.add(in.readAllBytes());
            }
        }, cursor -> { });

        assertThat(paths).as("the asset path stays the decoded listing path").containsExactly("org/my lib 1.0.jar");
        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0)).isEqualTo(jar);
    }

    @Test
    void a_folder_crawl_beyond_the_depth_cap_fails_cleanly_instead_of_overflowing_the_stack() {
        // A hostile or cyclic folder listing that keeps answering one more subfolder must hit the recursion cap with
        // a clear IOException, not a StackOverflowError.
        String proGated = "{\"errors\":[{\"status\":400,\"message\":"
                + "\"This REST API is available only in Artifactory Pro.\"}]}";
        String storage = "https://art.example/api/storage/libs-release";
        Map<String, ProxyFormat.Fetched> responses = new LinkedHashMap<>();
        responses.put(listUrl, new ProxyFormat.Fetched(400, proGated.getBytes(StandardCharsets.UTF_8), Map.of()));
        StringBuilder path = new StringBuilder();
        for (int depth = 0; depth < 80; depth++) {
            responses.put(storage + path, ok("{\"children\":[{\"uri\":\"/deeper\",\"folder\":true}]}"));
            path.append("/deeper");
        }
        ArtifactorySource source = new ArtifactorySource(base, repository, format, new FakeFetcher(responses));

        assertThatThrownBy(() -> source.forEach((assetFormat, assetPath, content) -> { }, cursor -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void a_generic_400_is_not_treated_as_pro_gated_and_still_throws() {
        FakeFetcher fetcher = new FakeFetcher(Map.of(listUrl, new ProxyFormat.Fetched(400,
                "{\"errors\":[{\"status\":400,\"message\":\"bad request\"}]}".getBytes(StandardCharsets.UTF_8),
                Map.of())));
        ArtifactorySource source = new ArtifactorySource(base, repository, format, fetcher);
        assertThatThrownBy(() -> source.forEach((assetFormat, path, content) -> { }, cursor -> { }))
                .isInstanceOf(IOException.class);
    }
}
