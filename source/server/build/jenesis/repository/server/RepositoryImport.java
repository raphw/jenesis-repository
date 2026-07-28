package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Publication;

/**
 * Drives a migration off an incumbent repository manager: it enumerates an {@link ImportSource} and routes each
 * asset to the first {@link RepositoryFormat} that also carries the {@link RepositoryImporter} capability and
 * {@link RepositoryImporter#imports imports} its format, writing it into the content-addressed store so the imported
 * repository serves and indexes it as its own. Formats are discovered with {@link java.util.ServiceLoader} and
 * filtered by {@code instanceof RepositoryImporter} (WSPI.2 (c): the importer is a format capability, not a second
 * discovered service), so the format coverage of an import is simply the set of importing formats on the module path:
 * the core ships Maven, Docker (OCI) and raw with the capability, and another format adds it by implementing the
 * interface. An asset whose format has no importing format is counted as skipped rather than failing the import, so a
 * mixed-format source migrates the formats this deployment understands and reports the rest - the same listing then
 * drives a second pass once those formats are on the path.
 *
 * <p>The import walk is an ingress <em>edge</em> (EPIC 26): it screens each asset before the demoted, layout-only
 * importer lays it out, so a migration off an incumbent lands the same {@link build.jenesis.repository.store.PublishInterceptor}
 * gate a deploy or batch upload passes - the deploy edge ({@link ScreenedDispatch}) and the import edge share the one
 * {@link Publication#screen} + restream + {@link Publication#published} choreography. For each asset the importer
 * {@link RepositoryImporter#importTarget describes} the target coordinate it will occupy; the edge
 * {@link Publication#screen screens} the asset against that descriptor and, on {@code ACCEPT}, restreams the stored
 * {@code blobs/<hash>} into {@link RepositoryImporter#importArtifact} then fires {@link Publication#published}. A
 * {@code QUARANTINE} is held (the screen diverted its blob to {@code /quarantine<target-path>}, never laid out) and a
 * {@code REJECT} is skipped; either way the walk continues to the next asset, so one screened-out artifact never
 * aborts a migration. An importer that {@link RepositoryImporter#importTarget describes} nothing (OCI, which owns its own
 * manifest choke point) has its bytes laid out unscreened here. With the free edition's empty discovered chain the
 * screen degrades to a store-then-restream and an accepted import is byte-for-byte what the pre-edge importer wrote.
 */
public final class RepositoryImport {

    private final List<RepositoryImporter> importers;

    public RepositoryImport() {
        this(ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    /** Filter the discovered (or supplied) formats to those carrying the {@link RepositoryImporter} capability - the
     *  {@code instanceof} split that replaces the second ServiceLoader pass. A base format without the capability is
     *  simply absent from the importer set, so its assets are skipped exactly as a missing importer's were. */
    public RepositoryImport(List<RepositoryFormat> formats) {
        this.importers = formats.stream()
                .filter(format -> format instanceof RepositoryImporter)
                .map(format -> (RepositoryImporter) format)
                .toList();
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
        AtomicInteger held = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> skippedFormats = new LinkedHashSet<>();
        source.forEach((format, path, content) -> {
            // A format configured off (jenesis.repository.<format>=false) imports nothing either - its assets
            // count as skipped, exactly as if its importer module were absent.
            if (Features.enabled(format)) {
                for (RepositoryImporter importer : importers) {
                    if (importer.imports(format)) {
                        screenAndLayout(importer, path, content, store, imported, held, rejected, listener);
                        return;
                    }
                }
            }
            skipped.incrementAndGet();
            skippedFormats.add(format);
            listener.skipped(format);
        }, listener::checkpoint);
        return new Result(imported.get(), skipped.get(), held.get(), rejected.get(), Set.copyOf(skippedFormats));
    }

    /** Screen one walked asset at the import edge, then route it by the chain's verdict: on {@code ACCEPT} restream the
     *  screened blob into the layout-only importer and fire {@link Publication#published}; on {@code QUARANTINE} leave
     *  it held (the screen already diverted its blob to {@code /quarantine<target-path>}); on {@code REJECT} skip it.
     *  An importer that describes no target coordinate (OCI) is laid out from the source stream unscreened. The walk
     *  continues past a held or rejected asset - one screened-out artifact never aborts a migration. */
    private void screenAndLayout(RepositoryImporter importer, String path, ImportSource.Content content,
                                 ArtifactStore store, AtomicInteger imported, AtomicInteger held,
                                 AtomicInteger rejected, Listener listener) throws IOException {
        Optional<ArtifactDescriptor> described = importer.importTarget(path);
        if (described.isEmpty()) {
            // No target coordinate to screen against (OCI owns its own manifest choke point): lay the asset out from
            // the source stream unchanged, exactly as before this edge screened.
            try (InputStream in = content.open()) {
                importer.importArtifact(path, in, store);
            }
            imported.incrementAndGet();
            listener.imported(path);
            return;
        }
        ArtifactDescriptor descriptor = described.get();
        Publication.Published outcome;
        try (InputStream in = content.open()) {
            // screen() stores the body content-addressed as it reads (never buffered whole) and runs the chain over the
            // real target coordinate; a QUARANTINE is already diverted to /quarantine<target-path> inside screen().
            outcome = new Publication(store).screen(descriptor, in);
        }
        switch (outcome.disposition()) {
            case ACCEPT -> {
                String blob = "blobs/" + outcome.hash();
                // Restream the screened blob into the layout-only importer - never the raw source download - so the
                // importer lays out exactly the bytes the gate saw, without holding the artifact in memory.
                try (InputStream restream = store.open(blob)) {
                    importer.importArtifact(path, restream, store);
                }
                new Publication(store).published(descriptor.withBlob(outcome.hash(), store.size(blob)));
                imported.incrementAndGet();
                listener.imported(path);
            }
            case QUARANTINE -> {
                held.incrementAndGet();
                listener.held(path, descriptor, outcome.hash());
            }
            case REJECT -> {
                rejected.incrementAndGet();
                listener.rejected(path, descriptor);
            }
        }
    }

    /** Observes an import as it runs: each asset imported, held, rejected or skipped, and each resume checkpoint (the
     *  cursor to resume from, or {@code null} at the end). The default {@link #NONE} ignores everything, and the
     *  {@code held}/{@code rejected} hooks default no-op so an existing caller is unaffected by the import edge's
     *  screening. */
    public interface Listener {

        Listener NONE = new Listener() {
        };

        /** An asset was imported (screened to {@code ACCEPT} and laid out); {@code path} is the source path (the
         *  coordinate) the walk just reached. */
        default void imported(String path) {
        }

        /** An asset was held: the import edge screened it to {@code QUARANTINE}, so its blob is diverted to
         *  {@code /quarantine<target-path>} for review and never laid out. {@code path} is the source path,
         *  {@code descriptor} the target-layout coordinate it was screened against, {@code hash} its stored blob - the
         *  replay context an edition records so a released hold can be re-driven into the importer later. */
        default void held(String path, ArtifactDescriptor descriptor, String hash) {
        }

        /** An asset was rejected: the import edge screened it to {@code REJECT}, so nothing was laid out (the orphan
         *  blob is left for garbage collection) and the walk continued. {@code descriptor} is the target coordinate. */
        default void rejected(String path, ArtifactDescriptor descriptor) {
        }

        default void skipped(String format) {
        }

        default void checkpoint(String cursor) throws IOException {
        }
    }

    /** The outcome of an import: how many assets were imported, how many were held (screened to quarantine) and
     *  rejected at the import edge, how many were skipped, and the formats skipped for want of an importer (empty on a
     *  complete import). */
    public record Result(int imported, int skipped, int held, int rejected, Set<String> skippedFormats) {
    }
}
