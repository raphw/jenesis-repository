package build.jenesis.repository.format.maven.test;

import build.jenesis.repository.format.maven.MavenMetadata;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconcile's {@code <latest>}/{@code <release>} screen for a version named there but ABSENT from {@code <versions>}
 * (Audit-26 disclosure Finding 2). The versions-block loop only screens versions it lists, so a held version named in
 * {@code <latest>}/{@code <release>} but not in {@code <versions>} would otherwise survive verbatim in the served
 * document. The named value is now screened directly through the same withhold seam, so a withheld latest/release is
 * re-derived (or dropped) even when the {@code <versions>} block is unchanged - while a document with nothing withheld
 * is still served byte-for-byte.
 */
class MavenMetadataReconcileLatestTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private MavenMetadata metadata;
    private Publication publication;

    private static final String COORD = "org/example/lib";
    private static final String DOCUMENT = "/maven/" + COORD + "/maven-metadata.xml";

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
        metadata = new MavenMetadata(store);
    }

    /** Publish a real version folder (a jar leaf) under the coordinate so it lists as a servable folder. */
    private void publishVersion(String version) throws IOException {
        String hash = publication.storeBlob(
                new ByteArrayInputStream(("jar-" + version).getBytes(StandardCharsets.UTF_8)));
        publication.link("/maven/" + COORD + "/" + version + "/lib-" + version + ".jar", hash);
    }

    /** Store a publisher-authored maven-metadata.xml verbatim (the default-serve document the reconcile screens). */
    private void storeDocument(String xml) throws IOException {
        String hash = publication.storeBlob(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        publication.link(DOCUMENT, hash);
    }

    /** Retroactively withhold a version by standing a /quarantine review pointer under its folder. */
    private void withhold(String version) throws IOException {
        store.write("publish/quarantine/maven/" + COORD + "/" + version + "/lib-" + version + ".jar",
                new ByteArrayInputStream("held".getBytes(StandardCharsets.UTF_8)));
    }

    private static String document(String latest, String release, String... versions) {
        StringBuilder v = new StringBuilder();
        for (String version : versions) {
            v.append("\n      <version>").append(version).append("</version>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<metadata>\n"
                + "  <groupId>org.example</groupId>\n"
                + "  <artifactId>lib</artifactId>\n"
                + "  <versioning>\n"
                + "    <latest>" + latest + "</latest>\n"
                + "    <release>" + release + "</release>\n"
                + "    <versions>" + v + "\n    </versions>\n"
                + "  </versioning>\n"
                + "</metadata>\n";
    }

    @Test
    void a_withheld_latest_absent_from_versions_is_dropped_from_the_served_document() throws IOException {
        // The publisher's document names 2.0 as latest/release but only lists 1.0 in <versions> (2.0 absent from the
        // block). 2.0 is then withheld. The versions-block loop never sees 2.0, so only the direct latest/release screen
        // catches it - the served latest/release must not name the held 2.0.
        publishVersion("1.0");
        storeDocument(document("2.0", "2.0", "1.0"));
        withhold("2.0");

        String served = new String(metadata.computed(DOCUMENT).orElseThrow(), StandardCharsets.UTF_8);

        assertThat(served).as("the held version name never survives in the served document").doesNotContain("2.0");
        assertThat(served).as("latest is re-derived to the newest screened version").contains("<latest>1.0</latest>");
        assertThat(served).as("release is re-derived to the newest screened non-SNAPSHOT")
                .contains("<release>1.0</release>");
    }

    @Test
    void a_named_latest_absent_from_versions_is_preserved_when_nothing_is_withheld() throws IOException {
        // The same internally-inconsistent document, but 2.0 is NOT withheld: the publisher's latest/release are
        // preserved verbatim (behavior-preservation - the screen only fires on a withheld named value).
        publishVersion("1.0");
        storeDocument(document("2.0", "2.0", "1.0"));

        String served = new String(metadata.computed(DOCUMENT).orElseThrow(), StandardCharsets.UTF_8);

        assertThat(served).as("an unheld named latest is left as the publisher wrote it").contains("<latest>2.0</latest>");
        assertThat(served).contains("<release>2.0</release>");
    }

    @Test
    void a_withheld_latest_that_is_the_only_version_drops_the_element_entirely() throws IOException {
        // 2.0 is named latest/release, listed, and the only version - and it is withheld. The screened set is empty, so
        // the reconcile removes the elements rather than name the held version.
        storeDocument(document("2.0", "2.0", "2.0"));
        withhold("2.0");

        String served = new String(metadata.computed(DOCUMENT).orElseThrow(), StandardCharsets.UTF_8);

        assertThat(served).as("no held name survives anywhere in the served document").doesNotContain("2.0");
        assertThat(served).doesNotContain("<latest>").doesNotContain("<release>");
    }
}
