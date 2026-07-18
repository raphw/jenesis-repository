package build.jenesis.repository.server;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishedAssets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A stably-ordered, resumable walk of a repository's published assets, layering format/coordinate enrichment over the
 * shared {@link PublishedAssets} pointer-tree walk ({@code publish/<request-path> -> <sha256>}) - the read-side of the
 * free {@code /api/assets} enumeration and the outbound mirror of the import connectors. The walk itself (its
 * depth-first ordering, its quarantine exclusion, its withheld-pointer skipping, its pointer-only metadata read) lives
 * once in {@link PublishedAssets}; this catalogue adds only the owning format's name and neutral coordinate to each
 * emitted {@link PublishedAssets.Entry}, through the format's {@link ArtifactLayout#describe describe} -
 * <strong>no artifact blob is ever opened</strong>, in keeping with the read-first bias.
 *
 * <p>The order and cursor semantics are {@link PublishedAssets}': a depth-first walk of the pointer tree where the
 * {@code '/'} separator sorts below every other character, and a page is resumed by an opaque cursor (the last emitted
 * asset's path). A page is a bounded slice of pointer metadata, the only full materialization the streaming principle
 * allows.
 */
public final class AssetCatalog {

    private final PublishedAssets assets;
    private final Function<String, Optional<RepositoryFormat>> owner;

    /**
     * @param store the doubly-scoped ({@code root.scope(tenant).scope(repository)}) artifact space to enumerate.
     * @param owner resolves a request path to the format that owns it (typically {@link FormatDispatcher#owner}),
     *              used to label each asset with its format name and neutral coordinate.
     */
    public AssetCatalog(ArtifactStore store, Function<String, Optional<RepositoryFormat>> owner) {
        this.assets = new PublishedAssets(store);
        this.owner = owner;
    }

    /** One enumerated asset: its serving request path (leading slash), stored size and SHA-256 straight from the
     *  publication pointer, its owning format name, and - when the format exposes a coordinate layout - its neutral
     *  ecosystem/coordinate/version and prerelease flag ({@code null}/{@code false} for a coordinate-less format
     *  such as raw). */
    public record Asset(String path,
                        long size,
                        String sha256,
                        String format,
                        String ecosystem,
                        String coordinate,
                        String version,
                        boolean prerelease) {
    }

    /** A bounded page of assets plus the cursor to resume after the last one, or {@code null} when the walk is
     *  exhausted (the terminal signal an importer loops until). */
    public record Page(List<Asset> assets, String cursor) {
    }

    /**
     * The next page after {@code cursor} (the last path of the previous page, or {@code null} to start), holding at
     * most {@code limit} assets. A full page carries a non-null {@link Page#cursor()} to fetch the next; a page that
     * exhausts the walk carries {@code null}.
     */
    public Page page(String cursor, int limit) throws IOException {
        List<Asset> page = new ArrayList<>();
        // Ask the shared walk for one extra to learn whether a further page exists without a second walk: > limit
        // means there is a next cursor, otherwise the walk is exhausted. The cursor is the relative path (no leading
        // slash) the walk resumes strictly past.
        assets.walk(cursor, limit + 1, entry -> page.add(enrich(entry)));
        if (page.size() > limit) {
            Asset last = page.get(limit - 1);
            return new Page(List.copyOf(page.subList(0, limit)), last.path().substring(1));
        }
        return new Page(List.copyOf(page), null);
    }

    /** Label a walked pointer with its owning format's name and - when the format lays out a coordinate - its neutral
     *  ecosystem/coordinate/version, the only enrichment this catalogue adds over the store-level walk. */
    private Asset enrich(PublishedAssets.Entry entry) {
        String requestPath = entry.path();
        Optional<RepositoryFormat> format = owner.apply(requestPath);
        Optional<ArtifactDescriptor> descriptor = format
                .filter(ArtifactLayout.class::isInstance)
                .flatMap(layout -> ((ArtifactLayout) layout).describe(requestPath));
        return new Asset(requestPath,
                entry.size(),
                entry.sha256(),
                format.map(RepositoryFormat::name).orElse(null),
                descriptor.map(ArtifactDescriptor::ecosystem).orElse(null),
                descriptor.map(ArtifactDescriptor::coordinate).orElse(null),
                descriptor.map(ArtifactDescriptor::version).orElse(null),
                descriptor.map(ArtifactDescriptor::prerelease).orElse(false));
    }
}
