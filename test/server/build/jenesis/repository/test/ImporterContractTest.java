package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The free-core half of the cross-format {@link RepositoryImporter} contract (the enterprise half lives in the gateway
 * test module): the coordinate-derivation and leading-slash invariants every importer must uphold, plus a ratchet that
 * fails if a newly-registered importer has no contract coverage. Importers are discovered exactly as the server does -
 * {@code ServiceLoader.load(RepositoryFormat.class)} filtered to the import capability - so the test works through the
 * SPI alone and needs no per-importer wiring.
 *
 * <p>Free core ships three importers of two shapes: {@link #COORDINATE Maven} is coordinate+versioned (its parsed
 * version is a traversal-free store segment, and a leading-slash H2 path resolves identically); {@code raw} returns a
 * descriptor for <em>every</em> asset (it is the un-inspected catch-all, so it never declines) and {@code oci} returns
 * {@code Optional.empty()} for {@code importTarget} by design (OCI owns its own manifest screening choke point). The two
 * special shapes are asserted directly; the ratchet then confirms every discovered importer is one of the three.
 */
class ImporterContractTest {

    record Case(String format, String deepPath, String nonDistributionPath) {
    }

    private static final List<Case> COORDINATE = List.of(
            new Case("maven", "org/example/lib/1.0/lib-1.0.jar", "org/example/lib/maven-metadata.xml"));

    private static List<RepositoryImporter> discovered() {
        return ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(RepositoryImporter.class::isInstance)
                .map(RepositoryImporter.class::cast)
                .toList();
    }

    private static RepositoryImporter importerFor(String format) {
        return discovered().stream()
                .filter(importer -> importer.imports(format))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no registered importer claims the format '" + format + "'"));
    }

    @Test
    void a_coordinate_versioned_importer_derives_a_traversal_safe_coordinate_and_normalises_a_leading_slash() {
        for (Case testCase : COORDINATE) {
            RepositoryImporter importer = importerFor(testCase.format());
            String label = testCase.format() + " (" + testCase.deepPath() + ")";

            ArtifactDescriptor deep = importer.importTarget(testCase.deepPath())
                    .orElseThrow(() -> new AssertionError(label + ": a deep incumbent path must resolve"));
            assertThat(deep.version()).as(label + ": the parsed version never carries a path slash")
                    .isNotNull().doesNotContain("/");

            ArtifactDescriptor absolute = importer.importTarget("/" + testCase.deepPath())
                    .orElseThrow(() -> new AssertionError(label + ": the leading-slash absolute path must resolve"));
            assertThat(absolute).as(label + ": a Nexus 3.71 leading-slash path resolves identically").isEqualTo(deep);

            assertThat(importer.importTarget(testCase.nonDistributionPath()))
                    .as(label + ": a non-distribution asset is declined").isEmpty();
        }
    }

    @Test
    void the_raw_importer_screens_every_asset_and_normalises_a_leading_slash() {
        RepositoryImporter raw = importerFor("raw");
        ArtifactDescriptor descriptor = raw.importTarget("dir/file.txt")
                .orElseThrow(() -> new AssertionError("raw screens every asset, so importTarget is never empty"));
        assertThat(raw.importTarget("/dir/file.txt")).as("a leading-slash raw path resolves identically")
                .hasValue(descriptor);
    }

    @Test
    void the_oci_importer_declines_import_target_by_design() {
        RepositoryImporter oci = importerFor("oci");
        assertThat(oci.importTarget("v2/app/manifests/1.0"))
                .as("OCI owns its own manifest screening, so importTarget is empty").isEmpty();
        assertThat(oci.importTarget("/v2/app/blobs/sha256:abc")).isEmpty();
    }

    @Test
    void every_registered_importer_has_a_contract_row() {
        List<RepositoryImporter> importers = discovered();
        assertThat(importers).as("the ServiceLoader discovers the free-core importers").isNotEmpty();
        for (RepositoryImporter importer : importers) {
            boolean covered = COORDINATE.stream().anyMatch(testCase -> importer.imports(testCase.format()))
                    || importer.imports("raw") || importer.imports("oci") || importer.imports("docker");
            assertThat(covered)
                    .as("the discovered importer " + importer.getClass().getName() + " has contract coverage - add it "
                            + "to ImporterContractTest so its coordinate derivation is checked")
                    .isTrue();
        }
    }
}
