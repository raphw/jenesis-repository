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
 * <p>The order is a depth-first walk of the pointer tree - name-sorted siblings, each container fully descended
 * before the next sibling - so a caller pages by an opaque cursor (the last emitted asset's path) resumed by
 * skipping every entry that sorts at or before it. Because the walk descends {@code data/} before the sibling leaf
 * {@code data.txt}, the cursor order treats the {@code '/'} separator as sorting below every other character (see
 * {@link #compare}), which is <em>not</em> {@link String#compareTo} order; comparing the two the naive way drops or
 * duplicates a file-vs-directory sibling across a page boundary. A page is a bounded slice of pointer metadata, the
 * only full materialization the streaming principle allows.
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
     *  or before {@code after}, so the resume can prune it without descending. In the walk's emission order every
     *  leaf beneath the node carries {@code childRelative + "/"} as a strict prefix and so sorts immediately after
     *  the node-as-leaf and before any sibling; the whole subtree is therefore already consumed exactly when
     *  {@code after} sorts strictly past {@code childRelative} without lying inside the subtree (the cursor being the
     *  node itself, or a leaf beneath it, means the descendants still have to be walked). The comparison must use
     *  {@link #compare} - the '/' separator sorts <em>below</em> every other character in emission order, the
     *  opposite of {@link String#compareTo} where '/' (0x2F) sits above '.', '-', … - or a file-vs-directory sibling
     *  interleaves and is dropped or duplicated across the page boundary. */
    private static boolean skip(String childRelative, String after) {
        if (after == null) {
            return false;
        }
        if (after.equals(childRelative) || after.startsWith(childRelative + "/")) {
            return false; // the cursor is this node or lies inside its subtree - descend to resume just past it
        }
        return compare(after, childRelative) > 0; // the whole subtree sorts before the cursor - already paged out
    }

    /** Compare two request paths in the walk's <em>emission</em> order: a depth-first descent over {@code '/'}
     *  -separated segments, which is the order {@link #collect} yields leaves in. That makes the separator sort
     *  below every other character (a container's leaves page before a sibling leaf whose name extends the
     *  container's past a lower character - {@code data/x} before {@code data.txt}), unlike {@link String#compareTo}
     *  where {@code '/'} (0x2F) outranks {@code '.'}, {@code '-'} and the digits. Resuming a cursor with the wrong
     *  order silently drops or repeats such siblings. */
    private static int compare(String left, String right) {
        int shared = Math.min(left.length(), right.length());
        for (int index = 0; index < shared; index++) {
            char first = left.charAt(index), second = right.charAt(index);
            if (first != second) {
                return rank(first) - rank(second);
            }
        }
        return left.length() - right.length();
    }

    /** The separator sorts below every other character, so a subtree pages before a sibling that extends its name. */
    private static int rank(char character) {
        return character == '/' ? -1 : character;
    }

    private void emit(String relative, String after, List<Asset> assets) throws IOException {
        if (after != null && compare(relative, after) <= 0) {
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
