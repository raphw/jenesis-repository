package build.jenesis.repository.server;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A stably-ordered, resumable walk of a repository's published assets, derived from the {@link Publication}
 * pointer tree ({@code publish/<request-path> -> <sha256>}) over a doubly-scoped {@link ArtifactStore} - the
 * read-side of the free {@code /api/assets} enumeration and the outbound mirror of the import connectors. It is a
 * pure metadata walk: each entry's path, size and SHA-256 come straight from the pointer (the pointer's content
 * <em>is</em> the hex digest; the size is a {@code blobs/<hash>} stat), and the coordinate/ecosystem come from the
 * owning format's {@link ArtifactLayout#describe describe} - <strong>no artifact blob is ever opened</strong>, in
 * keeping with the read-first bias. A path a {@link build.jenesis.repository.store.PublishInterceptor} withholds
 * (a retracted or quarantined artifact) is skipped through {@link Publication#located}, so the enumeration serves
 * exactly what a {@code GET} would, and the {@code /quarantine} review subtree is never walked.
 *
 * <p>The order is the store's lexicographic key order (both the filesystem and object-store backends already sort
 * their listings), so a caller pages by an opaque cursor - the last emitted asset's path - resumed by skipping
 * every entry that sorts at or before it, exactly the name-compare resume the Artifactory connector uses. A page
 * is a bounded slice of pointer metadata, the only full materialization the streaming principle allows.
 */
public final class AssetCatalog {

    private static final String ROOT = "publish";
    private static final String QUARANTINE = "quarantine";

    private final ArtifactStore store;
    private final Publication publication;
    private final Function<String, Optional<RepositoryFormat>> owner;

    /**
     * @param store the doubly-scoped ({@code root.scope(tenant).scope(repository)}) artifact space to enumerate.
     * @param owner resolves a request path to the format that owns it (typically {@link FormatDispatcher#owner}),
     *              used to label each asset with its format name and neutral coordinate.
     */
    public AssetCatalog(ArtifactStore store, Function<String, Optional<RepositoryFormat>> owner) {
        this.store = store;
        this.publication = new Publication(store);
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
        List<Asset> assets = new ArrayList<>();
        // Collect one extra to learn whether a further page exists without a second walk: > limit means there is a
        // next cursor, otherwise the walk is exhausted.
        collect("", cursor, limit + 1, assets);
        if (assets.size() > limit) {
            Asset last = assets.get(limit - 1);
            return new Page(List.copyOf(assets.subList(0, limit)), last.path().substring(1));
        }
        return new Page(List.copyOf(assets), null);
    }

    private void collect(String relative, String after, int cap, List<Asset> assets) throws IOException {
        if (assets.size() >= cap) {
            return;
        }
        List<String> children = store.list(relative.isEmpty() ? ROOT : ROOT + "/" + relative);
        if (children.isEmpty()) {
            if (!relative.isEmpty()) {
                emit(relative, after, assets);
            }
            return;
        }
        for (String child : children) {
            if (assets.size() >= cap) {
                return;
            }
            if (relative.isEmpty() && child.equals(QUARANTINE)) {
                // The quarantine review subtree is stored but never served; it is not an enumerable asset.
                continue;
            }
            String childRelative = relative.isEmpty() ? child : relative + "/" + child;
            if (skip(childRelative, after)) {
                continue;
            }
            collect(childRelative, after, cap, assets);
        }
    }

    /** Whether the entire {@code childRelative} node - both a leaf at that path and every leaf beneath it - sorts at
     *  or before {@code after}, so the resume can prune it without descending. Leaves beneath the node all carry the
     *  {@code childRelative + "/"} separator as a strict prefix, so the subtree is fully consumed exactly when
     *  {@code after} sorts at or after that separator without lying within it; the node-as-leaf is consumed when it
     *  sorts at or before {@code after}. Both must hold, since the '/' separator (0x2F) sits between '.' and '0', so
     *  a sibling name can otherwise interleave. */
    private static boolean skip(String childRelative, String after) {
        if (after == null) {
            return false;
        }
        String separator = childRelative + "/";
        boolean subtreeConsumed = after.compareTo(separator) >= 0 && !after.startsWith(separator);
        boolean leafConsumed = childRelative.compareTo(after) <= 0;
        return subtreeConsumed && leafConsumed;
    }

    private void emit(String relative, String after, List<Asset> assets) throws IOException {
        if (after != null && relative.compareTo(after) <= 0) {
            return;
        }
        String requestPath = "/" + relative;
        Optional<String> located = publication.located(requestPath);
        if (located.isEmpty()) {
            // Withheld (quarantine interceptor) or the blob is gone - not a served asset, so not enumerable.
            return;
        }
        String key = located.get();
        String hash = key.substring("blobs/".length());
        long size = store.size(key);
        Optional<RepositoryFormat> format = owner.apply(requestPath);
        Optional<ArtifactDescriptor> descriptor = format
                .filter(ArtifactLayout.class::isInstance)
                .flatMap(layout -> ((ArtifactLayout) layout).describe(requestPath));
        assets.add(new Asset(requestPath,
                size,
                hash,
                format.map(RepositoryFormat::name).orElse(null),
                descriptor.map(ArtifactDescriptor::ecosystem).orElse(null),
                descriptor.map(ArtifactDescriptor::coordinate).orElse(null),
                descriptor.map(ArtifactDescriptor::version).orElse(null),
                descriptor.map(ArtifactDescriptor::prerelease).orElse(false)));
    }
}
