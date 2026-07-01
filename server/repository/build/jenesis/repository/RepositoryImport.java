package build.jenesis.repository;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.source.ImportSource;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Drives a migration off an incumbent repository manager: it enumerates an {@link ImportSource} and routes each
 * asset to the first {@link RepositoryImporter} that {@link RepositoryImporter#handles handles} its format,
 * writing it into the content-addressed store so the imported repository serves and indexes it as its own. The
 * importers are discovered with {@link java.util.ServiceLoader}, so the format coverage of an import is simply the
 * set of importers on the module path: the core ships the Maven, Docker (OCI) and raw importers, and other format
 * importers can be added through the same SPI. An asset whose format has no importer is counted as skipped rather
 * than failing the import, so a mixed-format source migrates the formats this deployment understands and reports
 * the rest - the same listing then drives a second pass once those importers are on the path.
 */
public final class RepositoryImport {

    private final List<RepositoryImporter> importers;

    public RepositoryImport() {
        this(ServiceLoader.load(RepositoryImporter.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    public RepositoryImport(List<RepositoryImporter> importers) {
        this.importers = List.copyOf(importers);
    }

    /** Import every asset of {@code source} into {@code store}, returning the counts of what was imported and skipped. */
    public Result run(ImportSource source, ArtifactStore store) throws IOException {
        return run(source, store, Listener.NONE);
    }

    /** As {@link #run(ImportSource, ArtifactStore)}, reporting each imported and skipped asset and each resume
     *  checkpoint to {@code listener} - the seam an async job uses to track progress and persist a resume cursor. */
    public Result run(ImportSource source, ArtifactStore store, Listener listener) throws IOException {
        AtomicInteger imported = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        Set<String> skippedFormats = new LinkedHashSet<>();
        source.forEach((format, path, content) -> {
            for (RepositoryImporter importer : importers) {
                if (importer.handles(format)) {
                    try (InputStream in = content.open()) {
                        importer.importArtifact(path, in, store);
                    }
                    imported.incrementAndGet();
                    listener.imported();
                    return;
                }
            }
            skipped.incrementAndGet();
            skippedFormats.add(format);
            listener.skipped(format);
        }, listener::checkpoint);
        return new Result(imported.get(), skipped.get(), Set.copyOf(skippedFormats));
    }

    /** Observes an import as it runs: each asset imported or skipped, and each resume checkpoint (the cursor to
     *  resume from, or {@code null} at the end). The default {@link #NONE} ignores everything. */
    public interface Listener {

        Listener NONE = new Listener() {
        };

        default void imported() {
        }

        default void skipped(String format) {
        }

        default void checkpoint(String cursor) throws IOException {
        }
    }

    /** The outcome of an import: how many assets were imported, how many were skipped, and the formats skipped for
     *  want of an importer (empty on a complete import). */
    public record Result(int imported, int skipped, Set<String> skippedFormats) {
    }
}
