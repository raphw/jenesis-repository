package build.jenesis.repository.format.maven.test;

import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.format.maven.MavenMetadata;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven layout driven through {@link MavenFormat#handle}: a jar PUT is stored content-addressed and served back
 * (201/200), a missing artifact is a 404, a {@code maven-metadata.xml} PUT is stored verbatim like any artifact and
 * served back byte-for-byte, an absent one a 404 (W5.12 - deriving is no longer the default), and the opt-in
 * computation reconciles a stored document's version list or derives one for a coordinate no client uploaded.
 */
class MavenFormatTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final MavenFormat format = new MavenFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void name_and_handles_claim_only_the_maven_prefix() {
        assertThat(format.name()).isEqualTo("maven");
        assertThat(format.handles("/maven/org/example/lib/1.0/lib-1.0.jar")).isTrue();
        assertThat(format.handles("/raw/x")).isFalse();
    }

    @Test
    void reports_its_ecosystem_and_default_upstream() {
        assertThat(format.ecosystem()).isEqualTo("Maven");
        assertThat(format.defaultUpstream()).contains(java.net.URI.create("https://repo1.maven.org/maven2/"));
    }

    @Test
    void a_jar_is_published_and_served_and_a_miss_is_404() throws IOException {
        byte[] jar = "plain jar bytes".getBytes(StandardCharsets.UTF_8);

        FakeExchange put = new FakeExchange("PUT", "/maven/org/example/lib/1.0/lib-1.0.jar", jar);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);

        FakeExchange get = new FakeExchange("GET", "/maven/org/example/lib/1.0/lib-1.0.jar");
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(jar);

        FakeExchange miss = new FakeExchange("GET", "/maven/org/example/lib/1.0/absent.jar");
        format.handle(miss, store);
        assertThat(miss.status()).isEqualTo(404);
    }

    @Test
    void describe_maps_a_maven_path_to_its_neutral_coordinate() {
        assertThat(format.describe("/maven/org/example/lib/1.0/lib-1.0.jar")).hasValueSatisfying(descriptor -> {
            assertThat(descriptor.ecosystem()).isEqualTo("Maven");
            assertThat(descriptor.coordinate()).isEqualTo("org.example:lib");
            assertThat(descriptor.version()).isEqualTo("1.0");
            assertThat(descriptor.prerelease()).isFalse();
            assertThat(descriptor.path()).isEqualTo("/maven/org/example/lib/1.0/lib-1.0.jar");
        });

        assertThat(format.describe("/maven/org/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar"))
                .hasValueSatisfying(descriptor -> assertThat(descriptor.prerelease())
                        .as("a SNAPSHOT is a prerelease").isTrue());

        assertThat(format.describe("/maven/org/example/lib/maven-metadata.xml"))
                .as("generated metadata carries no coordinate to describe").isEmpty();
    }

    @Test
    void paths_returns_the_maven_directory_a_version_occupies() {
        assertThat(format.paths("org.example:lib", "1.0", store)).containsExactly("/maven/org/example/lib/1.0");
    }

    @Test
    void paths_also_resolves_the_module_mirror_of_a_published_modular_jar() throws IOException {
        format.handle(new FakeExchange("PUT", "/maven/org/example/lib/1.0/lib-1.0.jar",
                automaticModuleJar("org.example.lib")), store);

        assertThat(format.paths("org.example:lib", "1.0", store))
                .containsExactlyInAnyOrder("/maven/org/example/lib/1.0", "/module/org.example.lib/1.0");
    }

    @Test
    void paths_from_the_coordinate_alone_returns_the_primary_folder_without_reading_the_jar() throws IOException {
        // The read-path overload a console search uses: even with a modular jar published - whose /module/ mirror the
        // store overload discovers by opening the jar - the store-free overload returns only the primary folder, so a
        // search never buffers a blob to place its hits.
        format.handle(new FakeExchange("PUT", "/maven/org/example/lib/1.0/lib-1.0.jar",
                automaticModuleJar("org.example.lib")), store);

        assertThat(format.paths("org.example:lib", "1.0")).containsExactly("/maven/org/example/lib/1.0");
        assertThat(format.paths("no-colon", "1.0")).isEmpty();
    }

    private static byte[] automaticModuleJar(String moduleName) throws IOException {
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.jar.JarOutputStream jar = new java.util.jar.JarOutputStream(bytes, manifest)) {
            jar.flush();
        }
        return bytes.toByteArray();
    }

    @Test
    void a_metadata_document_and_its_checksums_are_stored_verbatim_and_served_back() throws IOException {
        byte[] document = ("<metadata>\n  <groupId>org.example</groupId>\n  <artifactId>lib</artifactId>\n"
                + "  <versioning>\n    <latest>1.0</latest>\n  </versioning>\n</metadata>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] sha1 = "AABBCCDD  maven-metadata.xml".getBytes(StandardCharsets.UTF_8);

        FakeExchange put = new FakeExchange("PUT", "/maven/org/example/lib/maven-metadata.xml", document);
        format.handle(put, store);
        assertThat(put.status()).isEqualTo(201);
        assertThat(publication.located("/maven/org/example/lib/maven-metadata.xml"))
                .as("a metadata document is now stored, not dropped").isPresent();

        FakeExchange putSha1 = new FakeExchange("PUT", "/maven/org/example/lib/maven-metadata.xml.sha1", sha1);
        format.handle(putSha1, store);
        assertThat(putSha1.status()).isEqualTo(201);

        FakeExchange getDocument = new FakeExchange("GET", "/maven/org/example/lib/maven-metadata.xml");
        format.handle(getDocument, store);
        assertThat(getDocument.status()).isEqualTo(200);
        assertThat(getDocument.responseBytes()).as("served byte-for-byte").isEqualTo(document);

        FakeExchange getSha1 = new FakeExchange("GET", "/maven/org/example/lib/maven-metadata.xml.sha1");
        format.handle(getSha1, store);
        assertThat(getSha1.responseBytes()).as("the stored checksum is served verbatim").isEqualTo(sha1);
    }

    @Test
    void a_metadata_get_is_404_by_default_when_none_was_uploaded() throws IOException {
        format.handle(new FakeExchange("PUT", "/maven/org/example/lib/1.0/lib-1.0.jar",
                "jar".getBytes(StandardCharsets.UTF_8)), store);

        FakeExchange metaGet = new FakeExchange("GET", "/maven/org/example/lib/maven-metadata.xml");
        format.handle(metaGet, store);
        assertThat(metaGet.status()).as("nothing is derived by default; an absent document is a 404").isEqualTo(404);
    }

    @Test
    void computation_reconciles_the_version_list_and_preserves_foreign_fields() throws IOException {
        // A stored document lists only 1.0 and carries a foreign field the server never derives; a 2.0 folder exists.
        String document = "<metadata>\n  <groupId>org.example</groupId>\n  <artifactId>lib</artifactId>\n"
                + "  <versioning>\n    <latest>9.9-CUSTOM</latest>\n    <lastUpdated>20200101000000</lastUpdated>\n"
                + "    <versions>\n      <version>1.0</version>\n    </versions>\n  </versioning>\n</metadata>";
        format.handle(new FakeExchange("PUT", "/maven/org/example/lib/maven-metadata.xml",
                document.getBytes(StandardCharsets.UTF_8)), store);
        publication.link("/maven/org/example/lib/2.0/lib-2.0.jar", "h2");

        FakeExchange get = FakeExchange.get("/maven/org/example/lib/maven-metadata.xml",
                Map.of(MavenMetadata.COMPUTE_SETTING, "true"));
        format.handle(get, store);
        String merged = get.responseText();

        assertThat(merged).as("the missing folder version is added")
                .contains("<version>1.0</version>").contains("<version>2.0</version>");
        assertThat(merged).as("every foreign field is preserved exactly as the publisher wrote it")
                .contains("<latest>9.9-CUSTOM</latest>").contains("<lastUpdated>20200101000000</lastUpdated>");

        FakeExchange getSha1 = FakeExchange.get("/maven/org/example/lib/maven-metadata.xml.sha1",
                Map.of(MavenMetadata.COMPUTE_SETTING, "true"));
        format.handle(getSha1, store);
        assertThat(getSha1.responseText()).as("the checksum is computed from the served (merged) bytes")
                .isEqualTo(sha1Hex(get.responseBytes()));
    }

    @Test
    void a_version_level_snapshot_document_passes_through_verbatim_under_computation() throws IOException {
        // A version-level SNAPSHOT document has no <versions> list (its versions live under <snapshotVersions>).
        byte[] snapshot = ("<metadata>\n  <versioning>\n    <snapshot>\n      <timestamp>20200101.000000</timestamp>\n"
                + "      <buildNumber>7</buildNumber>\n    </snapshot>\n  </versioning>\n</metadata>")
                .getBytes(StandardCharsets.UTF_8);
        format.handle(new FakeExchange("PUT", "/maven/org/example/lib/1.0-SNAPSHOT/maven-metadata.xml", snapshot),
                store);

        FakeExchange get = FakeExchange.get("/maven/org/example/lib/1.0-SNAPSHOT/maven-metadata.xml",
                Map.of(MavenMetadata.COMPUTE_SETTING, "true"));
        format.handle(get, store);
        assertThat(get.responseBytes()).as("a document with no version list passes through untouched")
                .isEqualTo(snapshot);
    }

    @Test
    void computation_derives_metadata_when_no_document_was_ever_uploaded() throws IOException {
        // The importer / batch case: version folders exist but no client uploaded a maven-metadata.xml.
        publication.link("/maven/org/example/lib/1.0/lib-1.0.jar", "h1");
        publication.link("/maven/org/example/lib/2.0/lib-2.0.jar", "h2");

        FakeExchange get = FakeExchange.get("/maven/org/example/lib/maven-metadata.xml",
                Map.of(MavenMetadata.COMPUTE_SETTING, "true"));
        format.handle(get, store);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseText()).contains("<version>1.0</version>").contains("<version>2.0</version>")
                .contains("<release>2.0</release>");
    }

    @Test
    void computation_keeps_the_stored_checksum_when_the_document_lists_every_version() throws IOException {
        // The stored document already lists the one stored folder version, so the merge changes nothing and the
        // publisher's own checksum stands - the server never re-authors an unchanged document's checksum.
        String document = "<metadata>\n  <versioning>\n    <versions>\n      <version>1.0</version>\n"
                + "    </versions>\n  </versioning>\n</metadata>";
        byte[] storedSha1 = "publisher-authored-checksum".getBytes(StandardCharsets.UTF_8);
        format.handle(new FakeExchange("PUT", "/maven/org/example/lib/maven-metadata.xml",
                document.getBytes(StandardCharsets.UTF_8)), store);
        format.handle(new FakeExchange("PUT", "/maven/org/example/lib/maven-metadata.xml.sha1", storedSha1), store);
        publication.link("/maven/org/example/lib/1.0/lib-1.0.jar", "h1");

        FakeExchange getDocument = FakeExchange.get("/maven/org/example/lib/maven-metadata.xml",
                Map.of(MavenMetadata.COMPUTE_SETTING, "true"));
        format.handle(getDocument, store);
        assertThat(getDocument.responseBytes()).as("nothing to add, so the document is served verbatim")
                .isEqualTo(document.getBytes(StandardCharsets.UTF_8));

        FakeExchange getSha1 = FakeExchange.get("/maven/org/example/lib/maven-metadata.xml.sha1",
                Map.of(MavenMetadata.COMPUTE_SETTING, "true"));
        format.handle(getSha1, store);
        assertThat(getSha1.responseBytes()).as("the stored checksum is served, not recomputed").isEqualTo(storedSha1);
    }

    @Test
    void metadata_is_proxied_fresh_from_upstream() throws IOException {
        byte[] upstreamDocument = "<metadata><fresh/></metadata>".getBytes(StandardCharsets.UTF_8);
        FakeExchange get = new FakeExchange("GET", "/maven/org/example/lib/maven-metadata.xml");

        boolean served = format.proxy(get, store, java.net.URI.create("https://upstream.example/maven2/"),
                (url, headers) -> Optional.of(new build.jenesis.repository.format.ProxyFormat.Fetched(
                        200, upstreamDocument, java.util.Map.of())));

        assertThat(served).isTrue();
        assertThat(get.responseBytes()).as("the mutable index is served fresh from upstream").isEqualTo(upstreamDocument);
        assertThat(publication.located("/maven/org/example/lib/maven-metadata.xml"))
                .as("a proxied metadata document is not cached").isEmpty();
    }

    private static String sha1Hex(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
