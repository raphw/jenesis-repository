package build.jenesis.repository.importer.index.test;

import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.index.IndexSourceProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The walk mechanics, driven through the provider over the fake enumerable format: assets stream in enumeration
 * order under the format's own name with bytes opened lazily, the cursor is the last consumed path checkpointed in
 * batches, a resume skips past the cursor without re-fetching what it skips, a cursor the index no longer carries
 * restarts the walk once, and a failing download or an index failing mid-walk surfaces as the import's failure.
 */
class IndexSourceTest {

    private final IndexSourceProvider provider = new IndexSourceProvider();

    private ImportSource source(FakeFetcher fetcher, String cursor) {
        ImportRequest request = new ImportRequest(URI.create("http://source.local"), ".").withFormat("fake");
        if (cursor != null) {
            request = request.withCursor(cursor);
        }
        ImportSource source = provider.create(request, fetcher);
        assertThat(source).isNotNull();
        return source;
    }

    private static FakeFetcher reachable() {
        return new FakeFetcher().on("http://source.local/", 200, new byte[0]);
    }

    @Test
    void the_walk_streams_the_formats_enumeration_lazily() throws IOException {
        FakeFetcher fetcher = reachable()
                .on("http://source.local/index", 200, ("alpha/a.bin http://files.local/a.bin\n"
                        + "beta/b.bin http://files.local/b.bin\n").getBytes(StandardCharsets.UTF_8))
                .on("http://files.local/a.bin", 200, "first".getBytes(StandardCharsets.UTF_8))
                .on("http://files.local/b.bin", 200, "second".getBytes(StandardCharsets.UTF_8));
        List<String> formats = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        source(fetcher, null).forEach((format, path, content) -> {
            formats.add(format);
            paths.add(path);
            try (InputStream in = content.open()) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }, cursors::add);
        assertThat(formats).allMatch("fake"::equals);
        assertThat(paths).containsExactly("alpha/a.bin", "beta/b.bin");
        assertThat(bodies).containsExactly("first", "second");
        assertThat(cursors).containsExactly("beta/b.bin", null);
    }

    @Test
    void an_unopened_asset_is_never_downloaded() throws IOException {
        FakeFetcher fetcher = reachable()
                .on("http://source.local/index", 200,
                        "alpha/a.bin http://files.local/a.bin\n".getBytes(StandardCharsets.UTF_8));
        source(fetcher, null).forEach((format, path, content) -> { }, cursor -> { });
        assertThat(fetcher.urls).doesNotContain("http://files.local/a.bin");
    }

    @Test
    void a_traversal_laced_index_path_is_skipped() throws IOException {
        FakeFetcher fetcher = reachable()
                .on("http://source.local/index", 200, ("../../etc/passwd http://files.local/evil.bin\n"
                        + "beta/b.bin http://files.local/b.bin\n").getBytes(StandardCharsets.UTF_8))
                .on("http://files.local/b.bin", 200, "second".getBytes(StandardCharsets.UTF_8));
        List<String> paths = new ArrayList<>();
        source(fetcher, null).forEach((format, path, content) -> {
            paths.add(path);
            content.open().close();
        }, cursor -> { });
        assertThat(paths).as("the traversal-laced enumerated path never reaches a store write")
                .containsExactly("beta/b.bin");
        assertThat(fetcher.urls).as("and its bytes are never fetched").doesNotContain("http://files.local/evil.bin");
    }

    @Test
    void checkpoints_batch_the_last_consumed_path() throws IOException {
        StringBuilder index = new StringBuilder();
        for (int i = 0; i < 130; i++) {
            index.append(String.format("p%03d", i)).append(" http://files.local/").append(i).append('\n');
        }
        FakeFetcher fetcher = reachable()
                .on("http://source.local/index", 200, index.toString().getBytes(StandardCharsets.UTF_8));
        List<String> cursors = new ArrayList<>();
        source(fetcher, null).forEach((format, path, content) -> { }, cursors::add);
        assertThat(cursors).containsExactly("p063", "p127", "p129", null);
    }

    @Test
    void a_resume_skips_past_the_cursor_without_downloading_what_it_skips() throws IOException {
        FakeFetcher fetcher = reachable()
                .on("http://source.local/index", 200, ("alpha/a.bin http://files.local/a.bin\n"
                        + "beta/b.bin http://files.local/b.bin\n").getBytes(StandardCharsets.UTF_8))
                .on("http://files.local/b.bin", 200, "second".getBytes(StandardCharsets.UTF_8));
        List<String> paths = new ArrayList<>();
        source(fetcher, "alpha/a.bin").forEach((format, path, content) -> {
            paths.add(path);
            content.open().close();
        }, cursor -> { });
        assertThat(paths).containsExactly("beta/b.bin");
        assertThat(fetcher.urls).doesNotContain("http://files.local/a.bin");
    }

    @Test
    void a_cursor_the_index_no_longer_carries_restarts_the_walk() throws IOException {
        FakeFetcher fetcher = reachable()
                .on("http://source.local/index", 200,
                        "alpha/a.bin http://files.local/a.bin\n".getBytes(StandardCharsets.UTF_8));
        List<String> paths = new ArrayList<>();
        source(fetcher, "gone/never.bin").forEach((format, path, content) -> paths.add(path), cursor -> { });
        assertThat(paths).containsExactly("alpha/a.bin");
        assertThat(fetcher.urls.stream().filter("http://source.local/index"::equals)).hasSize(2);
    }

    @Test
    void a_failing_download_fails_the_import() {
        FakeFetcher fetcher = reachable()
                .on("http://source.local/index", 200,
                        "alpha/a.bin http://files.local/a.bin\n".getBytes(StandardCharsets.UTF_8))
                .on("http://files.local/a.bin", 500, new byte[0]);
        assertThatThrownBy(() -> source(fetcher, null).forEach(
                (format, path, content) -> content.open().close(), cursor -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }

    @Test
    void an_index_failing_mid_walk_surfaces_as_the_imports_failure() {
        FakeFetcher fetcher = reachable()
                .on("http://source.local/index", 200, ("alpha/a.bin http://files.local/a.bin\n"
                        + "!boom\n").getBytes(StandardCharsets.UTF_8));
        List<String> paths = new ArrayList<>();
        assertThatThrownBy(() -> source(fetcher, null).forEach(
                (format, path, content) -> paths.add(path), cursor -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("mid-walk");
        assertThat(paths).containsExactly("alpha/a.bin");
    }
}
