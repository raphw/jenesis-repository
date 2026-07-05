package build.jenesis.repository.importer.jenesis.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.jenesis.JenesisSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The jenesis source walked against a canned {@code /api/assets} enumeration: it pages by the response cursor across
 * two pages, reports each asset with its format and the layout-relative path (the {@code /<format>/} serving prefix
 * stripped so the matching importer re-applies it), opens a download lazily from the source's {@code /repository}
 * serving path, checkpoints the resume cursor after each page (and a terminal null), sends the API key in the
 * {@code Jenesis-Repository-Key} header, and raises an {@code IOException} on a failed listing.
 */
class JenesisSourceTest {

    private final URI base = URI.create("https://src.example/");
    private final String repository = "libs";
    private final String listUrl = "https://src.example/api/assets?repo=libs";
    private final String page2Url = listUrl + "&cursor=tok1";
    private final String jarDownload = "https://src.example/repository/maven/org/example/lib/1.0/lib-1.0.jar";
    private final String rawDownload = "https://src.example/repository/raw/tools/installer.bin";

    private static ProxyFormat.Fetched ok(String body) {
        return new ProxyFormat.Fetched(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    @Test
    void it_pages_assets_and_reports_each_with_its_format_and_a_layout_path() throws IOException {
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        String page1 = "{\"assets\":[{\"path\":\"/maven/org/example/lib/1.0/lib-1.0.jar\",\"format\":\"maven\","
                + "\"size\":9,\"sha256\":\"abc\"}],\"cursor\":\"tok1\"}";
        String page2 = "{\"assets\":[{\"path\":\"/raw/tools/installer.bin\",\"format\":\"raw\","
                + "\"size\":3,\"sha256\":\"def\"}],\"cursor\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page1),
                page2Url, ok(page2),
                jarDownload, new ProxyFormat.Fetched(200, jar, Map.of()),
                rawDownload, new ProxyFormat.Fetched(200, new byte[]{1}, Map.of())));

        List<String> formats = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        List<byte[]> downloaded = new ArrayList<>();
        new JenesisSource(base, repository, fetcher).forEach((format, path, content) -> {
            formats.add(format);
            paths.add(path);
            if (path.endsWith("lib-1.0.jar")) {
                try (InputStream in = content.open()) {
                    downloaded.add(in.readAllBytes());
                }
            }
        }, cursors::add);

        assertThat(formats).containsExactly("maven", "raw");
        assertThat(paths).containsExactly("org/example/lib/1.0/lib-1.0.jar", "tools/installer.bin");
        assertThat(cursors).containsExactly("tok1", null);
        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0)).isEqualTo(jar);
    }

    @Test
    void it_resumes_from_a_checkpointed_cursor() throws IOException {
        String page2 = "{\"assets\":[{\"path\":\"/raw/tools/installer.bin\",\"format\":\"raw\","
                + "\"size\":3,\"sha256\":\"def\"}],\"cursor\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(page2Url, ok(page2)));

        List<String> paths = new ArrayList<>();
        new JenesisSource(base, repository, fetcher).from("tok1")
                .forEach((format, path, content) -> paths.add(path), cursor -> { });

        // The first page (listUrl) is never fetched - the walk resumes straight at the cursor's page.
        assertThat(paths).containsExactly("tools/installer.bin");
    }

    @Test
    void the_api_key_is_sent_on_the_listing_and_the_download() throws IOException {
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        String page1 = "{\"assets\":[{\"path\":\"/maven/org/example/lib/1.0/lib-1.0.jar\",\"format\":\"maven\","
                + "\"size\":9,\"sha256\":\"abc\"}],\"cursor\":null}";
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, ok(page1),
                jarDownload, new ProxyFormat.Fetched(200, jar, Map.of())));

        new JenesisSource(base, repository, fetcher).withKey("jenk_acme.secret")
                .forEach((format, path, content) -> {
                    try (InputStream in = content.open()) {
                        in.readAllBytes();
                    }
                }, cursor -> { });

        assertThat(fetcher.requests).hasSize(2).allSatisfy(headers ->
                assertThat(headers.get("Jenesis-Repository-Key")).isEqualTo("jenk_acme.secret"));
    }

    @Test
    void a_failed_listing_is_an_io_exception() {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                listUrl, new ProxyFormat.Fetched(500, new byte[0], Map.of())));
        JenesisSource source = new JenesisSource(base, repository, fetcher);
        assertThatThrownBy(() -> source.forEach((format, path, content) -> { }, cursor -> { }))
                .isInstanceOf(IOException.class);
    }
}
