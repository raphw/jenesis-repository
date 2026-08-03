package build.jenesis.repository.format.maven.test;

import build.jenesis.repository.format.maven.MavenMetadata;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The artifact-level {@code maven-metadata.xml} generated on read from the version folders published under a
 * coordinate: the version order follows a Maven-style comparison, {@code <release>} skips snapshots, and the
 * rendered bytes are a pure function of the version set so a checksum re-fetch is stable.
 */
class MavenMetadataTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private MavenMetadata metadata;

    private void publish(List<String> versions) throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        Publication publication = new Publication(store);
        for (String version : versions) {
            publication.link("/maven/org/example/lib/" + version + "/lib-" + version + ".jar", "abc" + version);
        }
        metadata = new MavenMetadata(store);
    }

    @Test
    void versions_are_listed_in_maven_order_with_latest_and_release() throws IOException {
        publish(List.of("1.10", "1.9", "1.0-alpha", "1.0", "2.0-SNAPSHOT"));
        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).contains("<groupId>org.example</groupId>").contains("<artifactId>lib</artifactId>");
        assertThat(xml).contains("<latest>2.0-SNAPSHOT</latest>");
        assertThat(xml).contains("<release>1.10</release>");
        assertThat(order(xml, "1.0-alpha", "1.0", "1.9", "1.10", "2.0-SNAPSHOT")).isTrue();
    }

    @Test
    void release_skips_snapshots_and_is_absent_when_all_are_snapshots() throws IOException {
        publish(List.of("1.0-SNAPSHOT", "2.0-SNAPSHOT"));
        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);
        assertThat(xml).contains("<latest>2.0-SNAPSHOT</latest>").doesNotContain("<release>");
    }

    @Test
    void checksums_match_the_xml_and_are_stable() throws IOException {
        publish(List.of("1.0", "2.0"));
        byte[] xml = metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow();
        byte[] sha1 = metadata.serve("/maven/org/example/lib/maven-metadata.xml.sha1").orElseThrow();
        assertThat(new String(sha1, StandardCharsets.UTF_8)).isEqualTo(sha1Hex(xml));
        assertThat(metadata.serve("/maven/org/example/lib/maven-metadata.xml.sha1").orElseThrow()).isEqualTo(sha1);
        assertThat(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow()).isEqualTo(xml);
    }

    @Test
    void an_unknown_coordinate_or_non_metadata_path_yields_nothing() throws IOException {
        publish(List.of("1.0"));
        assertThat(metadata.serve("/maven/org/example/missing/maven-metadata.xml")).isEmpty();
        assertThat(metadata.serve("/maven/org/example/lib/1.0/lib-1.0.jar")).isEmpty();
    }

    @Test
    void a_non_ascii_digit_version_does_not_crash_metadata_generation() throws IOException {
        // A version folder of Arabic-Indic digits is a numeric-looking token to Character.isDigit but not to
        // BigInteger; before the ASCII-only fix, comparing it threw NumberFormatException out of serve() (HTTP 500).
        publish(List.of("1.0", "١"));
        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);
        assertThat(xml).contains("<version>1.0</version>").contains("<version>١</version>");
    }

    @Test
    void a_metadata_checksum_sibling_is_not_mistaken_for_a_version() throws IOException {
        publish(List.of("1.0", "2.0"));
        // A .sha256 / .sha512 checksum sibling of maven-metadata.xml, deposited in the coordinate directory by a
        // client or cached there by the proxy, is not a version folder and must never be enumerated as a version.
        Publication publication = new Publication(store);
        publication.link("/maven/org/example/lib/maven-metadata.xml.sha256", "not-a-version");
        publication.link("/maven/org/example/lib/maven-metadata.xml.sha512", "not-a-version-either");

        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).contains("<version>1.0</version>").contains("<version>2.0</version>");
        assertThat(xml).doesNotContain("maven-metadata.xml.sha256").doesNotContain("maven-metadata.xml.sha512");
        assertThat(xml).contains("<release>2.0</release>");
    }

    @Test
    void the_reconcile_path_escapes_a_version_folder_name_with_xml_special_characters() throws IOException {
        // The opt-in compute/reconcile path rebuilds the <versions> block by hand from store.list folder names, which
        // are attacker-controlled and may carry an ampersand (a valid path/filename char). Appended raw it would emit a
        // bare '&' - malformed XML that every fetching Maven client fails to parse (a self-inflicted index DoS) - or
        // inject markup. The reconcile path must escape exactly as the StAX derivation path (serve) does.
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        Publication publication = new Publication(store);
        publication.link("/maven/org/example/lib/1.0/lib-1.0.jar", "abc1");
        publication.link("/maven/org/example/lib/a&b/lib-a&b.jar", "abc2");   // a version folder with a raw ampersand
        String stored = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n  <groupId>org.example</groupId>\n"
                + "  <artifactId>lib</artifactId>\n  <versioning>\n    <versions>\n      <version>1.0</version>\n"
                + "    </versions>\n  </versioning>\n</metadata>\n";
        publication.link("/maven/org/example/lib/maven-metadata.xml",
                publication.storeBlob(new ByteArrayInputStream(stored.getBytes(StandardCharsets.UTF_8))));
        metadata = new MavenMetadata(store);

        String xml = new String(metadata.computed("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).as("the missing version folder is reconciled in, XML-escaped")
                .contains("<version>a&amp;b</version>");
        assertThat(xml).as("never a bare ampersand that breaks the served document")
                .doesNotContain("<version>a&b</version>");
        assertThat(xml).as("the already-listed version survives, escaped once (not double-escaped)")
                .contains("<version>1.0</version>");
    }

    /** Withhold a published version by linking a {@code /quarantine<servedPath>} review pointer under it - the
     *  free-core hold convention every hold writer uses and the one {@code ServableNames.disclosableVersionFolder}
     *  reads (a version with &ge;1 quarantine child is held). The served pointer stays, so the version FOLDER is still
     *  enumerated by {@code store.list} - the screen, not the folder's absence, is what must hide it. */
    private void withhold(String version, String leaf) throws IOException {
        new Publication(store).link("/quarantine/maven/org/example/lib/" + version + "/" + leaf, "held-" + version);
    }

    @Test
    void a_withheld_version_is_absent_from_the_served_metadata_and_from_latest_and_release() throws IOException {
        // Trap #1: a version held after publication must not appear in <versions>, <latest> or <release>, even though
        // its folder is still enumerated. Revert the disclosableVersionFolder screen and 2.0 reappears - load-bearing.
        publish(List.of("1.0", "2.0"));
        withhold("2.0", "lib-2.0.jar");

        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).as("the held version's name never appears in the served index")
                .doesNotContain("<version>2.0</version>").doesNotContain("2.0");
        assertThat(xml).contains("<version>1.0</version>");
        assertThat(xml).as("latest/release are re-derived over the surviving set only")
                .contains("<latest>1.0</latest>").contains("<release>1.0</release>");
        // Its own artifact GET already 404s (located empty), so hiding the name only closes the enumeration leak.
        assertThat(new Publication(store).located("/maven/org/example/lib/2.0/lib-2.0.jar")).isEmpty();
    }

    @Test
    void a_fake_hash_version_with_no_stored_blob_is_still_listed() throws IOException {
        // Trap #2 - the exact case that reverted the last attempt: HIDE_WITHHELD stats no blob, so a version linked
        // with a fake hash and no stored blob (its GET 404s) is NOT withheld and MUST keep listing. The distinction
        // from trap #1 is the quarantine pointer alone - both versions here have fake hashes and no blob.
        publish(List.of("1.0", "3.0"));

        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).contains("<version>1.0</version>").contains("<version>3.0</version>");
        assertThat(new Publication(store).located("/maven/org/example/lib/3.0/lib-3.0.jar"))
                .as("blob is genuinely absent - the version is listed anyway, it is not withheld").isEmpty();
    }

    @Test
    void a_non_ascii_version_folder_is_screened_without_crashing() throws IOException {
        // Trap #3: the screen runs store.list over the version folder path; a non-ASCII folder name must not throw an
        // InvalidPathException out of metadata generation (the seam contains it and fails closed). A servable
        // non-ASCII version stays listed; a held non-ASCII version is hidden - both without a 500.
        publish(List.of("1.0", "naïve", "café"));
        withhold("café", "lib-café.jar");

        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).as("a servable non-ASCII version survives the screen, no InvalidPathException")
                .contains("<version>naïve</version>").contains("<version>1.0</version>");
        assertThat(xml).as("a held non-ASCII version is hidden").doesNotContain("<version>café</version>");
    }

    @Test
    void a_non_jar_packaging_version_is_screened_with_no_extension_heuristic() throws IOException {
        // Trap #4: the screen is packaging-neutral - the whole version folder is the unit, no .jar-leaf heuristic. A
        // .pom-only servable version lists; a .war-only held version hides.
        publish(List.of("1.0"));
        Publication publication = new Publication(store);
        publication.link("/maven/org/example/lib/5.0/lib-5.0.pom", "abc5");   // .pom-only, servable
        publication.link("/maven/org/example/lib/6.0/lib-6.0.war", "abc6");   // .war-only version...
        withhold("6.0", "lib-6.0.war");                                       // ...held

        String xml = new String(metadata.serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).as("a pom-only (non-jar) servable version is listed - no extension heuristic")
                .contains("<version>5.0</version>").contains("<version>1.0</version>");
        assertThat(xml).as("a held war-only version is hidden").doesNotContain("<version>6.0</version>");
    }

    @Test
    void reconcile_drops_a_version_the_stored_document_lists_once_it_is_withheld() throws IOException {
        // Trap #5: reconcileVersions must not re-add - and must actively remove - a withheld version, even one the
        // publisher's own stored document already lists. The union is screened on both legs (folders AND the stored
        // <versions>), so a version held after upload vanishes from the reconciled bytes.
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        Publication publication = new Publication(store);
        publication.link("/maven/org/example/lib/1.0/lib-1.0.jar", "abc1");
        publication.link("/maven/org/example/lib/2.0/lib-2.0.jar", "abc2");
        withhold("2.0", "lib-2.0.jar");
        String stored = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n  <groupId>org.example</groupId>\n"
                + "  <artifactId>lib</artifactId>\n  <versioning>\n    <versions>\n      <version>1.0</version>\n"
                + "      <version>2.0</version>\n    </versions>\n  </versioning>\n</metadata>\n";
        publication.link("/maven/org/example/lib/maven-metadata.xml",
                publication.storeBlob(new ByteArrayInputStream(stored.getBytes(StandardCharsets.UTF_8))));
        metadata = new MavenMetadata(store);

        String xml = new String(metadata.computed("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);

        assertThat(xml).as("the withheld version the stored document listed is removed on reconcile")
                .doesNotContain("<version>2.0</version>");
        assertThat(xml).contains("<version>1.0</version>");
    }

    private static boolean order(String xml, String... versions) {
        int previous = -1;
        for (String version : versions) {
            int index = xml.indexOf("<version>" + version + "</version>");
            if (index <= previous) {
                return false;
            }
            previous = index;
        }
        return true;
    }

    private static String sha1Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
