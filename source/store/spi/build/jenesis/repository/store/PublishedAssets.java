package build.jenesis.repository.store;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The one depth-first walk of a repository's {@code publish/} pointer tree ({@code publish/<request-path> ->
 * <sha256>}), shared by every reader that enumerates published assets so the walk - its ordering, its quarantine
 * exclusion, its withheld-pointer skipping and its pointer-only metadata read - lives in exactly one place rather
 * than being re-implemented per surface. The server's {@code /api/assets} catalogue layers format/coordinate
 * enrichment on top of it, and the console's NDJSON asset export writes each entry as it is reached; both are the
 * same walk with a different {@link Visitor}.
 *
 * <p>It is a pure metadata walk: each entry's path, size and SHA-256 come straight from the pointer (the pointer's
 * content <em>is</em> the hex digest; the size is a {@code blobs/<hash>} stat), <strong>no artifact blob is ever
 * opened</strong>, in keeping with the read-first bias. A path a {@link PublishInterceptor} withholds (a retracted or
 * quarantined artifact) is skipped through {@link Publication#located}, so the walk yields exactly what a {@code GET}
 * would, and the top-level {@code /quarantine} review subtree is never descended.
 *
 * <p>The order is a depth-first walk of the pointer tree - name-sorted siblings, each container fully descended
 * before the next sibling - so a caller can page by an opaque cursor (a resumed walk skips every entry that sorts at
 * or before it, {@link #walk(String, int, Visitor) walk}'s {@code after} argument). Because the walk descends
 * {@code data/} before the sibling leaf {@code data.txt}, the cursor order treats the {@code '/'} separator as
 * sorting below every other character (see {@link #compare}), which is <em>not</em> {@link String#compareTo} order;
 * comparing the two the naive way drops or duplicates a file-vs-directory sibling across a page boundary. A bounded
 * page is a slice of pointer metadata, the only full materialization the streaming principle allows.
 */
public final class PublishedAssets {

    /** The store subtree the walk is rooted at: the formats' published request-path pointer tree. */
    private static final String ROOT = "publish";

    /** The reserved top-level subtree under {@code publish/} holding artifacts the gate withholds: a {@code GET} does
     *  not serve them, so the walk never descends it. */
    private static final String QUARANTINE = "quarantine";

    private final ArtifactStore store;
    private final Publication publication;

    /** Walk the {@code publish/} tree of a doubly-scoped ({@code root.scope(tenant).scope(repository)}) store. */
    public PublishedAssets(ArtifactStore store) {
        this(store, new Publication(store));
    }

    /** The explicit seam: reuse a {@link Publication} already constructed over the same store rather than making a
     *  second (so the withheld-pointer check runs the caller's interceptor chain). */
    public PublishedAssets(ArtifactStore store, Publication publication) {
        this.store = store;
        this.publication = publication;
    }

    /** One walked pointer fact: the serving request path (leading slash), the blob's stored size, and the SHA-256 hex
     *  the pointer names - the format-neutral facts every reader shares, before any layout enrichment. */
    public record Entry(String path, long size, String sha256) {
    }

    /** A sink the walk hands each emitted {@link Entry} to, in emission order. */
    @FunctionalInterface
    public interface Visitor {
        void visit(Entry entry) throws IOException;
    }

    /**
     * Walk the pointer tree depth-first in emission order, handing each served leaf to {@code visitor}. When
     * {@code after} is non-null the walk resumes strictly past it (the relative path - no leading slash - of the last
     * entry a previous slice emitted), so a caller pages without re-emitting. At most {@code cap} entries are emitted;
     * pass {@link Integer#MAX_VALUE} for an unbounded walk (the whole-repository export). A caller that needs to learn
     * whether a further page exists asks for one more than it will keep and checks the count, as {@code AssetCatalog}
     * does.
     */
    public void walk(String after, int cap, Visitor visitor) throws IOException {
        collect("", after, cap, new int[]{0}, visitor);
    }

    private void collect(String relative, String after, int cap, int[] emitted, Visitor visitor) throws IOException {
        if (emitted[0] >= cap) {
            return;
        }
        List<String> children = store.list(relative.isEmpty() ? ROOT : ROOT + "/" + relative);
        if (children.isEmpty()) {
            if (!relative.isEmpty()) {
                emit(relative, after, emitted, visitor);
            }
            return;
        }
        for (String child : children) {
            if (emitted[0] >= cap) {
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
            collect(childRelative, after, cap, emitted, visitor);
        }
    }

    private void emit(String relative, String after, int[] emitted, Visitor visitor) throws IOException {
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
        visitor.visit(new Entry(requestPath, size, hash));
        emitted[0]++;
    }

    /** Whether the entire {@code childRelative} node - both a leaf at that path and every leaf beneath it - sorts at
     *  or before {@code after}, so a resume can prune it without descending. In the walk's emission order every leaf
     *  beneath the node carries {@code childRelative + "/"} as a strict prefix and so sorts immediately after the
     *  node-as-leaf and before any sibling; the whole subtree is therefore already consumed exactly when {@code after}
     *  sorts strictly past {@code childRelative} without lying inside the subtree (the cursor being the node itself,
     *  or a leaf beneath it, means the descendants still have to be walked). The comparison must use {@link #compare}
     *  - the '/' separator sorts <em>below</em> every other character in emission order, the opposite of
     *  {@link String#compareTo} where '/' (0x2F) sits above '.', '-', … - or a file-vs-directory sibling interleaves
     *  and is dropped or duplicated across the page boundary. */
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
     *  -separated segments, which is the order {@link #collect} yields leaves in. That makes the separator sort below
     *  every other character (a container's leaves page before a sibling leaf whose name extends the container's past
     *  a lower character - {@code data/x} before {@code data.txt}), unlike {@link String#compareTo} where {@code '/'}
     *  (0x2F) outranks {@code '.'}, {@code '-'} and the digits. Resuming a cursor with the wrong order silently drops
     *  or repeats such siblings. */
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
}
