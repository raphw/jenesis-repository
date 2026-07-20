package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the batch archive-ingestion feature (W5.11) over HTTP against a real server with the feature switched on and
 * a small entry cap. Proves that a single archive PUT carrying {@code X-Jenesis-Explode: zip} explodes into one
 * synthesized publish per entry through the normal format loop: entries of different formats each land in their own
 * layout, the discovered publication-screen chain (the test {@link MarkerInterceptor}) reaches a per-entry verdict so
 * a quarantined or rejected member never taints a stored sibling, a path-traversing entry is refused before it
 * touches the store, an entry no format claims is reported unclaimed, and the walk stops at the entry cap. Each
 * outcome is read off the per-entry manifest the batch returns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BatchUploadTest {

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private HttpClient client;
    private String base;

    @BeforeAll
    public void start() {
        System.setProperty("JENESIS_STORE_ROOT", root.toString());
        System.setProperty("jenesis.repository.batch-upload", "true");
        System.setProperty("jenesis.repository.batch-upload-max-entries", "5");
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        running = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + running.port() + "/repository";
    }

    @AfterAll
    public void stop() {
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
        System.clearProperty("jenesis.repository.batch-upload");
        System.clearProperty("jenesis.repository.batch-upload-max-entries");
    }

    @Test
    public void a_multi_format_archive_publishes_each_entry_through_its_format() throws Exception {
        byte[] raw = "generic bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pom = "<project><!--m--></project>".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("raw/dir/a.txt", raw);
        members.put("maven/org/example/lib/1.0/lib-1.0.pom", pom);

        HttpResponse<String> exploded = explode("/", zip(members));
        assertThat(exploded.statusCode()).isEqualTo(200);
        assertThat(exploded.body()).contains("\"path\":\"/raw/dir/a.txt\",\"status\":\"stored\"");
        assertThat(exploded.body()).contains("\"path\":\"/maven/org/example/lib/1.0/lib-1.0.pom\",\"status\":\"stored\"");
        assertThat(exploded.body()).contains("\"capped\":false");

        // Each member is served back from its own format's layout, byte for byte - it went through the format's
        // own publish path, not a buffered copy.
        assertThat(get("/raw/dir/a.txt").body()).isEqualTo("generic bytes");
        assertThat(get("/maven/org/example/lib/1.0/lib-1.0.pom").body()).isEqualTo("<project><!--m--></project>");
    }

    @Test
    public void a_per_entry_gate_verdict_is_reported_and_a_bad_entry_spares_its_siblings() throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("raw/gate/ok.txt", "kept".getBytes(StandardCharsets.UTF_8));
        members.put("raw/gate/gate-quarantine.txt", "held".getBytes(StandardCharsets.UTF_8));
        members.put("raw/gate/gate-reject.txt", "denied".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> exploded = explode("/", zip(members));
        assertThat(exploded.statusCode()).isEqualTo(200);
        assertThat(exploded.body()).contains("\"path\":\"/raw/gate/ok.txt\",\"status\":\"stored\"");
        assertThat(exploded.body()).contains("\"path\":\"/raw/gate/gate-quarantine.txt\",\"status\":\"quarantined\"");
        assertThat(exploded.body()).contains("\"path\":\"/raw/gate/gate-reject.txt\",\"status\":\"rejected\"");

        // The accepted sibling serves; the quarantined and rejected members are not served - the verdict was per entry.
        assertThat(get("/raw/gate/ok.txt").statusCode()).isEqualTo(200);
        assertThat(get("/raw/gate/gate-quarantine.txt").statusCode()).isEqualTo(404);
        assertThat(get("/raw/gate/gate-reject.txt").statusCode()).isEqualTo(404);
    }

    @Test
    public void a_traversing_or_unclaimed_entry_is_refused_or_reported_without_touching_a_sibling() throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("raw/keep/safe.txt", "safe".getBytes(StandardCharsets.UTF_8));
        members.put("../escape.txt", "evil".getBytes(StandardCharsets.UTF_8));
        members.put("/etc/passwd", "evil".getBytes(StandardCharsets.UTF_8));
        members.put("nosuchformat/x.txt", "orphan".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> exploded = explode("/", zip(members));
        assertThat(exploded.statusCode()).isEqualTo(200);
        assertThat(exploded.body()).contains("\"path\":\"/raw/keep/safe.txt\",\"status\":\"stored\"");
        // Both a relative-escape and an absolute entry are refused as path traversal before any store touch.
        assertThat(exploded.body()).contains("\"status\":\"rejected\",\"reason\":\"path-traversal\"");
        // A member no format claims is reported unclaimed, not stored.
        assertThat(exploded.body()).contains("\"path\":\"/nosuchformat/x.txt\",\"status\":\"unclaimed\"");

        assertThat(get("/raw/keep/safe.txt").statusCode()).isEqualTo(200);
        assertThat(get("/nosuchformat/x.txt").statusCode()).isEqualTo(404);
    }

    @Test
    public void the_walk_stops_at_the_entry_cap() throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            members.put("raw/cap/f" + i + ".txt", ("n" + i).getBytes(StandardCharsets.UTF_8));
        }

        HttpResponse<String> exploded = explode("/", zip(members));
        assertThat(exploded.statusCode()).isEqualTo(200);
        assertThat(exploded.body()).contains("\"capped\":true");
        // Exactly the cap of five members were processed; the sixth-onward were never read.
        assertThat(countOccurrences(exploded.body(), "\"status\":")).isEqualTo(5);
        assertThat(get("/raw/cap/f0.txt").statusCode()).isEqualTo(200);
        assertThat(get("/raw/cap/f7.txt").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> explode(String basePath, byte[] archive) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + basePath))
                .header("X-Jenesis-Explode", "zip")
                .PUT(BodyPublishers.ofByteArray(archive)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofString());
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
