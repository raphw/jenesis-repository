package build.jenesis.repository.test;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WSPI.2 (c): {@code RepositoryImporter} is no longer a discovered service but an {@code instanceof} capability on the
 * one {@link RepositoryFormat} seam, with {@code handles}&rarr;{@code imports} and {@code describe}&rarr;{@code
 * importTarget} renamed off the format's own methods. This pins the consolidation: a base format without the capability
 * is unaffected (its assets are skipped), an importing format is discovered and imports, {@link RepositoryImport}
 * filters the formats by the capability, and a layout-aware format carries both {@link ArtifactLayout#describe} and
 * {@link RepositoryImporter#importTarget} at once without an erasure clash.
 */
class ImporterCapabilityTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    /** A hosted-only format with no import capability - a base RepositoryFormat, never an importer. */
    private static final class BaseFormat implements RepositoryFormat {
        @Override
        public String name() {
            return "base";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/base/");
        }

        @Override
        public void handle(FormatExchange exchange, ArtifactStore store) {
        }
    }

    /** A format that also carries the migration-import capability, and is layout-aware too - so it implements
     *  {@link ArtifactLayout#describe} AND {@link RepositoryImporter#importTarget}, the two same-erasure methods the
     *  rename keeps distinct on one object. */
    private static final class ImportingFormat implements RepositoryFormat, ArtifactLayout, RepositoryImporter {

        final List<String> laidOut = new ArrayList<>();

        @Override
        public String name() {
            return "imp";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/imp/");
        }

        @Override
        public void handle(FormatExchange exchange, ArtifactStore store) {
        }

        // ArtifactLayout.describe(String) - the coordinate behind a request path.
        @Override
        public String ecosystem() {
            return "imp";
        }

        @Override
        public Optional<ArtifactDescriptor> describe(String path) {
            return Optional.of(ArtifactDescriptor.at("imp", path));
        }

        @Override
        public List<String> paths(String coordinate, String version, ArtifactStore store) {
            return List.of();
        }

        // RepositoryImporter - the migration write-half, distinct methods on the same object.
        @Override
        public boolean imports(String sourceFormat) {
            return sourceFormat.equals("impsrc");
        }

        @Override
        public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
            // Empty: lay out unscreened (the OCI-style path), so this unit needs no discovered screen chain.
            return Optional.empty();
        }

        @Override
        public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
            content.readAllBytes();
            laidOut.add(path);
        }
    }

    /** A fake source that emits one asset per (format, path) pair, content read lazily. */
    private static ImportSource source(Map<String, String> assetsByFormat) {
        return (consumer, checkpoint) -> {
            for (Map.Entry<String, String> asset : assetsByFormat.entrySet()) {
                consumer.accept(asset.getKey(), asset.getValue(),
                        () -> new ByteArrayInputStream(asset.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            checkpoint.reached(null);
        };
    }

    @Test
    void the_orchestrator_filters_formats_by_the_capability() throws IOException {
        BaseFormat base = new BaseFormat();
        ImportingFormat importing = new ImportingFormat();

        assertThat(base).as("a base format is not an importer").isNotInstanceOf(RepositoryImporter.class);
        assertThat(importing).as("a format that opts in IS an importer").isInstanceOf(RepositoryImporter.class);

        // The orchestrator is handed the discovered formats and filters by instanceof - a mixed source of an importing
        // format's asset and a base format's asset migrates the former and skips the latter.
        RepositoryImport orchestrator = new RepositoryImport(List.of(base, importing));
        RepositoryImport.Result result = orchestrator.run(
                source(new LinkedHashMap<>(Map.of("impsrc", "one/1", "basesrc", "two/2"))), store);

        assertThat(result.imported()).as("the importing format's asset is imported").isEqualTo(1);
        assertThat(result.skipped()).as("the base format's asset is skipped - no importing format claims it").isEqualTo(1);
        assertThat(result.skippedFormats()).containsExactly("basesrc");
        assertThat(importing.laidOut).containsExactly("one/1");
    }

    @Test
    void the_handles_to_imports_rename_selects_the_source_format() {
        ImportingFormat importing = new ImportingFormat();
        assertThat(importing.imports("impsrc")).isTrue();
        assertThat(importing.imports("other")).isFalse();
    }

    @Test
    void a_layout_aware_importing_format_carries_both_describe_and_importTarget() {
        ImportingFormat importing = new ImportingFormat();
        // describe (ArtifactLayout) and importTarget (RepositoryImporter) are distinct methods on the one object -
        // the rename is what lets a layout-aware format also be an importer without a same-erasure clash.
        assertThat(importing.describe("/imp/a/b")).as("the layout coordinate side is unaffected").isPresent();
        assertThat(importing.importTarget("a/b")).as("the importer's target side is its own method").isEmpty();
    }

    @Test
    void a_base_only_format_is_unaffected_and_imports_nothing() throws IOException {
        RepositoryImport orchestrator = new RepositoryImport(List.<RepositoryFormat>of(new BaseFormat()));
        RepositoryImport.Result result = orchestrator.run(
                source(new LinkedHashMap<>(Map.of("basesrc", "x/1"))), store);

        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.skippedFormats()).containsExactly("basesrc");
    }
}
