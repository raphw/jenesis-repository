package build.jenesis.repository.importer.maven.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.maven.MavenSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vendor-neutral Maven source walked against canned pages: the directory-listing walk descends the tree
 * depth-first in sorted order over plain autoindex HTML, reporting every artifact with format {@code maven} and
 * skipping metadata, checksum sidecars, dot directories and navigation chrome; it checkpoints each completed
 * subtree and a resumed walk neither re-emits nor re-lists what the cursor covers; a listing-less source falls back
 * to the legacy repository index refreshed per coordinate through {@code maven-metadata.xml}; and a source with
 * neither fails with an actionable message.
 */
class MavenSourceTest {

    private static final String ROOT = "https://repo.example/root/";

    private static ProxyFormat.Fetched ok(String body) {
        return new ProxyFormat.Fetched(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static ProxyFormat.Fetched status(int code) {
        return new ProxyFormat.Fetched(code, new byte[0], Map.of());
    }

    private static Map<String, ProxyFormat.Fetched> tree() {
        Map<String, ProxyFormat.Fetched> responses = new HashMap<>();
        responses.put(ROOT, ok("""
                <html><body><h1>Index of /root/</h1>
                <a href="../">../</a>
                <a href="?C=N;O=D">Name</a>
                <a href=".index/">.index/</a>
                <a href="com/">com/</a>
                <a href="maven-metadata.xml">maven-metadata.xml</a>
                <a href="top.jar">top.jar</a>
                <a href="top.jar.sha1">top.jar.sha1</a>
                <a href="https://repo.example/root/zeta/">zeta/</a>
                <a href="https://other.example/evil/">evil/</a>
                </body></html>"""));
        responses.put(ROOT + "com/", ok("<a href=\"../\">../</a><a href=\"acme/\">acme/</a>"));
        responses.put(ROOT + "com/acme/", ok("<a href=\"lib/\">lib/</a>"));
        responses.put(ROOT + "com/acme/lib/", ok("<a href=\"1.0/\">1.0/</a>"
                + "<a href=\"maven-metadata.xml\">maven-metadata.xml</a>"
                + "<a href=\"maven-metadata.xml.sha1\">maven-metadata.xml.sha1</a>"));
        responses.put(ROOT + "com/acme/lib/1.0/", ok("<a href=\"lib-1.0.jar\">lib-1.0.jar</a>"
                + "<a href=\"lib-1.0.pom\">lib-1.0.pom</a>"
                + "<a href=\"lib-1.0.jar.md5\">lib-1.0.jar.md5</a>"));
        responses.put(ROOT + "zeta/", ok("<a href=\"x%20y.jar\">x y.jar</a><a href=\"z.jar\">z.jar</a>"));
        responses.put(ROOT + "com/acme/lib/1.0/lib-1.0.jar", ok("jar-bytes"));
        responses.put(ROOT + "com/acme/lib/1.0/lib-1.0.pom", ok("pom-bytes"));
        responses.put(ROOT + "top.jar", ok("top-bytes"));
        responses.put(ROOT + "zeta/x%20y.jar", ok("spaced-bytes"));
        responses.put(ROOT + "zeta/z.jar", ok("z-bytes"));
        return responses;
    }

    private static MavenSource source(FakeFetcher fetcher) {
        return new MavenSource(URI.create("https://repo.example"), "root", fetcher);
    }

    @Test
    void a_directory_listing_is_walked_depth_first_skipping_sidecars_and_chrome() throws IOException {
        FakeFetcher fetcher = new FakeFetcher(tree());
        List<String> formats = new ArrayList<>(), paths = new ArrayList<>(), cursors = new ArrayList<>();
        List<byte[]> jar = new ArrayList<>();
        source(fetcher).forEach((format, path, content) -> {
            formats.add(format);
            paths.add(path);
            if (path.endsWith("lib-1.0.jar")) {
                try (InputStream in = content.open()) {
                    jar.add(in.readAllBytes());
                }
            }
        }, cursors::add);

        assertThat(paths).containsExactly(
                "com/acme/lib/1.0/lib-1.0.jar",
                "com/acme/lib/1.0/lib-1.0.pom",
                "top.jar",
                "zeta/x y.jar",
                "zeta/z.jar");
        assertThat(formats).allMatch("maven"::equals);
        assertThat(cursors).containsExactly(
                "tree:com/acme/lib/1.0/",
                "tree:com/acme/lib/",
                "tree:com/acme/",
                "tree:com/",
                "tree:zeta/",
                null);
        assertThat(jar).hasSize(1);
        assertThat(jar.get(0)).isEqualTo("jar-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(fetcher.urls).doesNotContain(ROOT + ".index/", "https://other.example/evil/");
    }

    @Test
    void a_resumed_walk_neither_re_emits_nor_re_lists_completed_subtrees() throws IOException {
        FakeFetcher fetcher = new FakeFetcher(tree());
        List<String> paths = new ArrayList<>(), cursors = new ArrayList<>();
        source(fetcher).from("tree:com/").forEach((format, path, content) -> paths.add(path), cursors::add);

        assertThat(paths).containsExactly("top.jar", "zeta/x y.jar", "zeta/z.jar");
        assertThat(cursors).containsExactly("tree:zeta/", null);
        assertThat(fetcher.urls).doesNotContain(ROOT + "com/", ROOT + "com/acme/lib/1.0/");
    }

    @Test
    void a_resume_within_a_subtree_prunes_around_the_cursor() throws IOException {
        FakeFetcher fetcher = new FakeFetcher(tree());
        List<String> paths = new ArrayList<>();
        source(fetcher).from("tree:com/acme/lib/1.0/").forEach((format, path, content) -> paths.add(path), cursor -> { });

        // 1.0 is complete but its ancestors are not: the walk descends com/ again without re-listing 1.0.
        assertThat(paths).containsExactly("top.jar", "zeta/x y.jar", "zeta/z.jar");
        assertThat(fetcher.urls).contains(ROOT + "com/acme/lib/").doesNotContain(ROOT + "com/acme/lib/1.0/");
    }

    @Test
    void a_nexus_shaped_landing_page_hops_to_its_advertised_index_and_files_download_from_their_canonical_urls()
            throws IOException {
        // The page shapes below are copied from a real sonatype/nexus3 3.70.4: the repository root is a landing
        // page linking the actual HTML index, whose directory rows link relatively but whose file rows link each
        // file at its canonical download URL under /repository/.
        String nexus = "http://nexus.example:8081";
        String repository = nexus + "/repository/maven-releases/";
        String browse = nexus + "/service/rest/repository/browse/maven-releases/";
        Map<String, ProxyFormat.Fetched> responses = new HashMap<>();
        responses.put(repository, ok("<html><head>"
                + "<link rel=\"stylesheet\" type=\"text/css\" href=\"../../static/css/nexus-content.css?3.70.4-02\"/>"
                + "</head><body><a href=\"../..\">logo</a>"
                + "<a href=\"../../#browse/browse:maven-releases\">Browse</a>"
                + "<p>This maven2 hosted repository is not directly browseable at this URL.</p>"
                + "<a href=\"../../service/rest/repository/browse/maven-releases/\">HTML index</a></body></html>"));
        responses.put(browse, ok("<body class=\"htmlIndex\"><table><tr><td><a href=\"org/\">org</a></td></tr></table></body>"));
        responses.put(browse + "org/", ok("<a href=\"../\">Parent Directory</a><a href=\"example/\">example</a>"));
        responses.put(browse + "org/example/", ok("<a href=\"../\">Parent Directory</a><a href=\"lib/\">lib</a>"));
        responses.put(browse + "org/example/lib/", ok("<a href=\"../\">Parent Directory</a><a href=\"1.0/\">1.0</a>"));
        responses.put(browse + "org/example/lib/1.0/", ok("<a href=\"../\">Parent Directory</a>"
                + "<a href=\"" + repository + "org/example/lib/1.0/lib-1.0.jar\">lib-1.0.jar</a>"
                + "<a href=\"" + repository + "org/example/lib/1.0/lib-1.0.jar.sha1\">lib-1.0.jar.sha1</a>"
                + "<a href=\"" + repository + "org/example/lib/1.0/lib-1.0.pom\">lib-1.0.pom</a>"));
        responses.put(repository + "org/example/lib/1.0/lib-1.0.jar", ok("nexus-jar"));
        responses.put(repository + "org/example/lib/1.0/lib-1.0.pom", ok("nexus-pom"));
        FakeFetcher fetcher = new FakeFetcher(responses);

        List<String> paths = new ArrayList<>(), cursors = new ArrayList<>();
        Map<String, byte[]> bodies = new HashMap<>();
        new MavenSource(URI.create(nexus + "/repository"), "maven-releases", fetcher)
                .forEach((format, path, content) -> {
                    paths.add(path);
                    try (InputStream in = content.open()) {
                        bodies.put(path, in.readAllBytes());
                    }
                }, cursors::add);

        assertThat(paths).containsExactly("org/example/lib/1.0/lib-1.0.jar", "org/example/lib/1.0/lib-1.0.pom");
        assertThat(bodies.get("org/example/lib/1.0/lib-1.0.jar"))
                .isEqualTo("nexus-jar".getBytes(StandardCharsets.UTF_8));
        assertThat(cursors).containsExactly(
                "tree:org/example/lib/1.0/",
                "tree:org/example/lib/",
                "tree:org/example/",
                "tree:org/",
                null);
    }

    @Test
    void a_failed_directory_listing_is_an_io_exception() {
        Map<String, ProxyFormat.Fetched> responses = tree();
        responses.put(ROOT + "com/", status(500));
        MavenSource source = source(new FakeFetcher(responses));
        assertThatThrownBy(() -> source.forEach((format, path, content) -> { }, cursor -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }

    @Test
    void credentials_are_sent_as_a_basic_authorization_header() throws IOException {
        Map<String, ProxyFormat.Fetched> responses = new HashMap<>();
        responses.put(ROOT, ok("<a href=\"only.jar\">only.jar</a>"));
        responses.put(ROOT + "only.jar", ok("bytes"));
        FakeFetcher fetcher = new FakeFetcher(responses);
        source(fetcher).withCredentials("user", "secret").forEach((format, path, content) -> {
            try (InputStream in = content.open()) {
                in.readAllBytes();
            }
        }, cursor -> { });

        String expected = "Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(fetcher.requests).isNotEmpty()
                .allSatisfy(headers -> assertThat(headers.get("Authorization")).isEqualTo(expected));
    }

    @Test
    void disabled_listing_falls_back_to_the_index_refreshed_through_maven_metadata() throws IOException {
        String root = "https://mirror.example/repo/";
        Map<String, ProxyFormat.Fetched> responses = new HashMap<>();
        responses.put(root, status(403));
        responses.put(root + ".index/nexus-maven-repository-index.properties", ok("nexus.index.id=repo\n"));
        responses.put(root + ".index/nexus-maven-repository-index.gz", new ProxyFormat.Fetched(200, index(List.of(
                record("u", "org.acme|lib|1.0|NA|NA", "i", "jar|123|456|0|0|0|jar"),
                record("u", "org.acme|lib|1.0|sources|jar", "i", "jar|123|456|0|0|0|jar"),
                record("u", "org.acme|gone|1.0|NA|NA", "del", "1"),
                record("DESCRIPTOR", "NexusIndex"),
                record("u", "org..evil|x|1.0|NA|NA", "i", "jar|1|1|0|0|0|jar"))), Map.of()));
        responses.put(root + "org/acme/lib/maven-metadata.xml", ok("""
                <metadata>
                  <groupId>org.acme</groupId><artifactId>lib</artifactId><version>9.9</version>
                  <versioning><versions>
                    <version>1.0</version>
                    <version>1.1</version>
                    <version>1.2</version>
                  </versions></versioning>
                </metadata>"""));
        String pom = "<project><packaging>bundle</packaging></project>";
        responses.put(root + "org/acme/lib/1.1/lib-1.1.pom", ok(pom));
        responses.put(root + "org/acme/lib/1.2/lib-1.2.pom", ok("<project><packaging>pom</packaging></project>"));
        FakeFetcher fetcher = new FakeFetcher(responses);

        List<String> paths = new ArrayList<>(), cursors = new ArrayList<>();
        Map<String, byte[]> bodies = new HashMap<>();
        new MavenSource(URI.create("https://mirror.example/repo"), ".", fetcher).forEach((format, path, content) -> {
            assertThat(format).isEqualTo("maven");
            paths.add(path);
            if (path.endsWith("lib-1.1.pom")) {
                try (InputStream in = content.open()) {
                    bodies.put(path, in.readAllBytes());
                }
            }
        }, cursors::add);

        assertThat(paths).containsExactly(
                "org/acme/lib/1.0/lib-1.0.jar",
                "org/acme/lib/1.0/lib-1.0.pom",
                "org/acme/lib/1.0/lib-1.0-sources.jar",
                "org/acme/lib/1.1/lib-1.1.pom",
                "org/acme/lib/1.1/lib-1.1.jar",
                "org/acme/lib/1.2/lib-1.2.pom");
        assertThat(cursors).containsExactly("meta:org/acme/lib", null);
        assertThat(bodies.get("org/acme/lib/1.1/lib-1.1.pom")).isEqualTo(pom.getBytes(StandardCharsets.UTF_8));
        assertThat(fetcher.urls).doesNotContain(root + "org/acme/gone/maven-metadata.xml");
    }

    @Test
    void an_index_walk_resumes_by_record_position() throws IOException {
        String root = "https://mirror.example/repo/";
        List<Map<String, String>> records = new ArrayList<>();
        for (int at = 0; at < 520; at++) {
            records.add(record("u", "g|a|" + at + "|NA|NA", "i", "jar|1|1|0|0|0|jar"));
        }
        Map<String, ProxyFormat.Fetched> responses = new HashMap<>();
        responses.put(root, status(403));
        responses.put(root + ".index/nexus-maven-repository-index.properties", ok("nexus.index.id=repo\n"));
        responses.put(root + ".index/nexus-maven-repository-index.gz", new ProxyFormat.Fetched(200, index(records), Map.of()));
        responses.put(root + "g/a/maven-metadata.xml", status(404));

        List<String> first = new ArrayList<>(), firstCursors = new ArrayList<>();
        MavenSource source = new MavenSource(URI.create("https://mirror.example/repo"), ".", new FakeFetcher(responses));
        source.forEach((format, path, content) -> first.add(path), firstCursors::add);
        assertThat(first).hasSize(1040);   // each record emits its jar and implies its pom
        assertThat(firstCursors).containsExactly("index:512", "meta:g/a", null);

        List<String> resumed = new ArrayList<>(), resumedCursors = new ArrayList<>();
        source.from("index:512").forEach((format, path, content) -> resumed.add(path), resumedCursors::add);
        assertThat(resumed).hasSize(16).allSatisfy(path -> assertThat(path).matches("g/a/51[2-9]/a-51[2-9]\\.(jar|pom)"));
        assertThat(resumedCursors).containsExactly("meta:g/a", null);

        List<String> refreshed = new ArrayList<>();
        source.from("meta:g/a").forEach((format, path, content) -> refreshed.add(path), cursor -> { });
        assertThat(refreshed).isEmpty();   // the replay rebuilds coordinates without re-importing anything
    }

    @Test
    void a_source_with_neither_listing_nor_index_fails_with_an_actionable_message() {
        Map<String, ProxyFormat.Fetched> responses = new HashMap<>();
        responses.put(ROOT, status(404));
        responses.put(ROOT + ".index/nexus-maven-repository-index.properties", status(404));
        MavenSource source = source(new FakeFetcher(responses));
        assertThatThrownBy(() -> source.forEach((format, path, content) -> { }, cursor -> { }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("directory listing")
                .hasMessageContaining("repository index");
    }

    private static Map<String, String> record(String... fields) {
        Map<String, String> record = new LinkedHashMap<>();
        for (int at = 0; at < fields.length; at += 2) {
            record.put(fields[at], fields[at + 1]);
        }
        return record;
    }

    /** The legacy index chunk format, as the reader expects it: a GZIP stream of version byte, timestamp long, then
     *  per record an int field count and per field a flag byte, a modified-UTF-8 name, an int length and the value
     *  bytes - each record's {@code i} field GZIP-compressed to exercise the per-field compression flag. */
    private static byte[] index(List<Map<String, String>> records) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                out.writeByte(1);
                out.writeLong(1234567890L);
                for (Map<String, String> record : records) {
                    out.writeInt(record.size());
                    for (Map.Entry<String, String> field : record.entrySet()) {
                        boolean compressed = field.getKey().equals("i");
                        byte[] value = field.getValue().getBytes(StandardCharsets.UTF_8);
                        if (compressed) {
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                                gzip.write(value);
                            }
                            value = buffer.toByteArray();
                        }
                        out.writeByte(compressed ? 0x08 : 0);
                        out.writeUTF(field.getKey());
                        out.writeInt(value.length);
                        out.write(value);
                    }
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
