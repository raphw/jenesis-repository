package build.jenesis.repository.importer.nexus.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.nexus.NexusSource;
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
 * The Nexus source walked against a canned components API: it pages by continuation token across two pages, reports
 * each asset with its component format, its path and a lazily-opened download, checkpoints the resume token after each
 * page (and a terminal null), sends basic credentials as an {@code Authorization} header, and raises an
 * {@code IOException} on a failed listing. The credentials never travel to a cross-origin download URL, and a
 * traversal-laced asset path is skipped before it can reach a store write.
 */
class NexusSourceTest {

    private final URI base = URI.create("https://nexus.example/");
    private final String repository = "maven-releases";
    private final String listUrl = "https://nexus.example/service/rest/v1/components?repository=maven-releases";
    private final String page2Url = listUrl + "&continuationToken=tok1";
    private final String downloadUrl = "https://nexus.example/download/lib-1.0.jar";

    private static ProxyFormat.Fetched ok(String body) {
        return new ProxyFormat.Fetched(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    @Test
    void it_pages_components_and_reports_each_asset_with_its_format_and_a_resume_cursor() throws IOException {
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        String page1 = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/example/lib/1.0/lib-1.0.jar\","
                + "\"downloadUrl\":\"" + downloadUrl + "\"}]}],\"continuationToken\":\"tok1\"}";
        String page2 = "{\"items\":[{\"format\":\"docker\",\"assets\":[{\"path\":\"v2/app/manifests/1.0\","
                + "\"downloadUrl\":\"https://nexus.example/download/manifest\"}]}],\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page1),
                page2Url, ok(page2),
                downloadUrl, new ProxyFormat.Fetched(200, jar, Map.of()),
                "https://nexus.example/download/manifest", new ProxyFormat.Fetched(200, new byte[]{1}, Map.of())));

        List<String> formats = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        List<byte[]> downloaded = new ArrayList<>();
        new NexusSource(base, repository, fetcher).forEach((format, path, content) -> {
            formats.add(format);
            paths.add(path);
            if (path.endsWith("lib-1.0.jar")) {
                try (InputStream in = content.open()) {
                    downloaded.add(in.readAllBytes());
                }
            }
        }, cursors::add);

        assertThat(formats).containsExactly("maven2", "docker");
        assertThat(paths).containsExactly("org/example/lib/1.0/lib-1.0.jar", "v2/app/manifests/1.0");
        assertThat(cursors).containsExactly("tok1", null);
        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0)).isEqualTo(jar);
    }

    @Test
    void credentials_are_sent_as_a_basic_authorization_header() throws IOException {
        FakeFetcher fetcher = new FakeFetcher(Map.of(listUrl, ok("{\"items\":[],\"continuationToken\":null}")));
        new NexusSource(base, repository, fetcher)
                .withCredentials("user", "secret")
                .forEach((format, path, content) -> { }, cursor -> { });

        String expected = "Basic "
                + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(fetcher.requests).isNotEmpty()
                .allSatisfy(headers -> assertThat(headers.get("Authorization")).isEqualTo(expected));
    }

    @Test
    void credentials_are_not_forwarded_to_a_cross_origin_download_url() throws IOException {
        // The download URL comes off the listing; a compromised or misconfigured Nexus naming another host must not
        // receive the operator's basic credentials - the cross-origin download goes out unauthenticated.
        String foreign = "https://elsewhere.example/download/lib-1.0.jar";
        String page = "{\"items\":[{\"format\":\"maven2\",\"assets\":[{\"path\":\"org/example/lib/1.0/lib-1.0.jar\","
                + "\"downloadUrl\":\"" + foreign + "\"}]}],\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page),
                foreign, new ProxyFormat.Fetched(200, new byte[]{1}, Map.of())));

        new NexusSource(base, repository, fetcher).withCredentials("user", "secret")
                .forEach((format, path, content) -> {
                    try (InputStream in = content.open()) {
                        in.readAllBytes();
                    }
                }, cursor -> { });

        String expected = "Basic "
                + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(fetcher.urls).containsExactly(listUrl, foreign);
        assertThat(fetcher.requests.get(0).get("Authorization")).as("the listing is authenticated").isEqualTo(expected);
        assertThat(fetcher.requests.get(1)).as("the cross-origin download is not").doesNotContainKey("Authorization");
    }

    @Test
    void a_traversal_laced_asset_path_is_skipped() throws IOException {
        String page = "{\"items\":[{\"format\":\"maven2\",\"assets\":["
                + "{\"path\":\"../../auth/keys\",\"downloadUrl\":\"https://nexus.example/download/evil\"},"
                + "{\"path\":\"org/example/ok.jar\",\"downloadUrl\":\"https://nexus.example/download/ok\"}]}],"
                + "\"continuationToken\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page),
                "https://nexus.example/download/ok", new ProxyFormat.Fetched(200, new byte[]{1}, Map.of())));

        List<String> paths = new ArrayList<>();
        new NexusSource(base, repository, fetcher).forEach((format, path, content) -> paths.add(path), cursor -> { });

        assertThat(paths).as("the hostile path never reaches the consumer (and is never downloaded)")
                .containsExactly("org/example/ok.jar");
        assertThat(fetcher.urls).containsExactly(listUrl);
    }

    @Test
    void a_failed_listing_is_an_io_exception() {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, new ProxyFormat.Fetched(500, new byte[0], Map.of())));
        NexusSource source = new NexusSource(base, repository, fetcher);
        assertThatThrownBy(() -> source.forEach((format, path, content) -> { }, cursor -> { }))
                .isInstanceOf(IOException.class);
    }
}
