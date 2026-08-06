package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
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
 * special shapes are asserted directly; the shared {@link ContractCensus} ratchet then confirms every statically
 * declared format provider is runtime-visible and has importer coverage or a reason-bearing exemption.
 */
class ImporterContractTest {

    private static final String JENESIS_FORMAT =
            "build.jenesis.repository.format.jenesis.JenesisFormat";

    record Case(String format, String deepPath, String nonDistributionPath) {
    }

    private static final List<Case> COORDINATE = List.of(
            new Case("maven", "org/example/lib/1.0/lib-1.0.jar", "org/example/lib/maven-metadata.xml"));

    private static List<RepositoryImporter> discovered() {
        return discoveredFormats().stream()
                .filter(RepositoryImporter.class::isInstance)
                .map(RepositoryImporter.class::cast)
                .toList();
    }

    private static List<RepositoryFormat> discoveredFormats() {
        return ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get)
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
    void every_declared_importer_provider_has_a_contract_row() throws IOException {
        List<String> fixtures = Stream.concat(
                        COORDINATE.stream().map(testCase -> importerFor(testCase.format())),
                        Stream.of(importerFor("raw"), importerFor("oci")))
                .map(importer -> importer.getClass().getName())
                .toList();
        List<Provider> runtime = discoveredFormats().stream()
                .map(format -> Provider.runtime(format.name(), format))
                .toList();

        ContractCensus.of(RepositoryImporter.class,
                ContractCensus.declaredProviders(repositoryRoot().resolve("source"), RepositoryFormat.class),
                runtime,
                fixtures,
                List.of(new Exemption(JENESIS_FORMAT,
                        "the Jenesis module layout does not implement the migration-import capability")));
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("source").resolve("format"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
