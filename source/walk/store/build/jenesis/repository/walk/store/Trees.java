package build.jenesis.repository.walk.store;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The reusable iterative deep-walk over an {@link ArtifactStore}'s key layout - the descent primitive
 * {@link StoreArtifactWalk} drives its segment traversal with, extracted so no format ever hand-rolls a store tree
 * walk again (the recurring shape behind every "someone re-invented a recursive descent" defect: Debian's
 * {@code collectDebs}, the OCI backfill, and the reference walk itself all re-implemented exactly this before their
 * fixes). Given a store and a root prefix, {@link #descend} visits every stored leaf under it in <em>path order</em>
 * - the total order a name-sorted depth-first descent produces, where {@code '/'} sorts below every other character,
 * so a subtree ({@code app/...}) is visited wholly before a longer sibling name it prefixes ({@code app.txt}).
 *
 * <p><strong>Iterative depth, paged width, O(depth) memory.</strong> The descent is driven by an explicit stack of
 * in-progress container cursors, never self-recursion, so an arbitrarily deep key (a many-segment Maven groupId, a
 * multi-segment OCI name - depth is client-planted and, absent {@link ArtifactStore#key} capping, uncapped at the
 * routing edge) is walked to completion instead of overflowing the call stack. Each level holds at most one buffered
 * {@link ArtifactStore#page} page ({@value #PAGE} sibling names), so the resident memory is the stack of in-progress
 * containers - O(key-path depth) - and a flat millions-entry namespace is paged, never materialised as one list.
 *
 * <p><strong>Steering.</strong> The plain {@link #descend(ArtifactStore, String, Visitor)} with a bare
 * {@link Visitor} walks the whole subtree and emits every leaf. A {@link Visitor} that overrides {@link Visitor#seek},
 * {@link Visitor#ceiling}, {@link Visitor#enters} and {@link Visitor#emits} confines the descent to a half-open key
 * range and seeks into it - what {@link StoreArtifactWalk}'s range-segmented, resumable walk needs, expressed once
 * here rather than duplicated per consumer.
 */
public final class Trees {

    private Trees() {
    }

    /** Sibling names fetched per {@link ArtifactStore#page} call - the enumeration buffer width, and the only
     *  per-level memory the descent holds. */
    public static final int PAGE = 1000;

    /**
     * Steers a {@link #descend} walk: what to do with each emitted leaf, and - for a bounded consumer - which
     * subtrees to enter, which leaves fall in range, where to seek in first, and where the sibling scan stops. The
     * bound methods default to an unbounded full-subtree walk, so a consumer that only cares about the leaves
     * implements {@link #visit} alone. Every bound is compared under {@linkplain Trees#order path order}, the same
     * order the visit sequence follows, so cursors and range edges stay exactly consistent with what is visited.
     */
    public interface Visitor {

        /** A stored leaf key that {@link #emits} accepted, delivered in path order. */
        void visit(String key) throws IOException;

        /** Whether a stored leaf key falls in range and should be {@link #visit visited}; every leaf by default. */
        default boolean emits(String key) {
            return true;
        }

        /** Whether any key under {@code prefix/} can still fall in range - a {@code false} prunes the whole subtree
         *  without descending or paging it; every container by default. */
        default boolean enters(String prefix) {
            return true;
        }

        /** The full key to seek to first inside a container that contains it (the resume cursor or range start),
         *  descended ahead of the paged siblings and without the {@link #ceiling} guard; {@code null} to start every
         *  container from its first child. */
        default String seek() {
            return null;
        }

        /** The exclusive upper key bound the sibling scan stops at - sorted siblings at or past it cannot be in range,
         *  so paging ends there; {@code null} for no upper bound (page to the end of each container). */
        default String ceiling() {
            return null;
        }
    }

    /**
     * Walk every stored key under {@code prefix} in path order, delivering each to {@code visitor} - an iterative
     * depth-first descent (explicit container stack, no recursion) that consumes the store exclusively through
     * {@link ArtifactStore#page}, so an arbitrarily deep key never overflows the stack and a flat huge namespace is
     * paged rather than buffered. A key where {@link ArtifactStore#exists} is a leaf; a name with children is a
     * container descended before its later siblings (pre-order). When {@code visitor} overrides the bound methods the
     * descent is confined to that half-open range and seeks into it; a bare visitor walks the whole subtree.
     */
    public static void descend(ArtifactStore store, String prefix, Visitor visitor) throws IOException {
        new Descent(store, visitor).run(prefix);
    }

    /**
     * The walk's total key order - <em>path order</em>, what a name-sorted depth-first descent visits: character by
     * character with {@code '/'} sorting below every other character, a shorter key before any longer one it prefixes.
     * Plain string order would put a subtree {@code app/...} after a sibling leaf {@code app.txt} ({@code '.'} sorts
     * below {@code '/'}) although the descent, ordering siblings by name, visits the {@code app} subtree first;
     * comparing under path order keeps cursors and range bounds exactly consistent with the visit sequence.
     */
    public static int order(String left, String right) {
        int length = Math.min(left.length(), right.length());
        for (int index = 0; index < length; index++) {
            char first = left.charAt(index), second = right.charAt(index);
            if (first != second) {
                if (first == '/') {
                    return -1;
                }
                if (second == '/') {
                    return 1;
                }
                return Character.compare(first, second);
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    /** One in-flight descent: the ordered depth-first traversal driven by an explicit {@link Frame} stack, so the
     *  memory cost is the stack of in-progress containers - O(key-path depth) - and no key depth can overflow the
     *  call stack. */
    private static final class Descent {

        private final ArtifactStore store;
        private final Visitor visitor;

        private Descent(ArtifactStore store, Visitor visitor) {
            this.store = store;
            this.visitor = visitor;
        }

        private void run(String root) throws IOException {
            Frame top = open(root);
            if (top == null) {
                return; // the root was a leaf (emitted if in range) or a non-intersecting subtree
            }
            Deque<Frame> stack = new ArrayDeque<>();
            stack.push(top);
            while (!stack.isEmpty()) {
                String child = stack.peek().next();
                if (child == null) {
                    stack.pop(); // this container is drained (or reached the upper bound); ascend
                    continue;
                }
                Frame descended = open(child);
                if (descended != null) {
                    stack.push(descended); // a container to descend into, before its later siblings - pre-order
                }
            }
        }

        /** Process one node: a stored key is a leaf ({@link Visitor#visit} it when {@link Visitor#emits in range}) and
         *  yields no frame; a subtree the visitor will not {@link Visitor#enters enter} is pruned and yields no frame;
         *  any other name is a container to descend, returned as a fresh {@link Frame}. */
        private Frame open(String key) throws IOException {
            if (store.exists(key)) {
                if (visitor.emits(key)) {
                    visitor.visit(key);
                }
                return null;
            }
            if (!visitor.enters(key)) {
                return null;
            }
            return new Frame(key);
        }

        /** One container's child cursor: its ordered child enumeration, made resumable so the driver holds a stack of
         *  these instead of a call stack. {@link #next} returns the next child key to descend - the seek-path child
         *  first (descended without the {@link Visitor#ceiling} guard, its own {@link Visitor#enters} prune still
         *  applying the bound), then the paged siblings, ending (yielding {@code null}) at the ceiling or when the last
         *  short page drains. */
        private final class Frame {

            private final String key;
            /** The seek-path child name to descend first, or {@code null} when the {@link Visitor#seek} target is not
             *  inside this container. */
            private final String seekChild;
            private boolean seekYielded;
            private List<String> page;
            private int position;

            private Frame(String key) {
                this.key = key;
                String low = visitor.seek();
                if (low != null && low.startsWith(key + "/")) {
                    String rest = low.substring(key.length() + 1);
                    int slash = rest.indexOf('/');
                    this.seekChild = slash < 0 ? rest : rest.substring(0, slash);
                } else {
                    this.seekChild = null;
                }
            }

            /** The next child key to descend, or {@code null} once this container is exhausted. */
            private String next() {
                if (seekChild != null && !seekYielded) {
                    // The seek-path child, descended first and WITHOUT the ceiling guard; its own enters() prune (in
                    // open) still applies the upper bound.
                    seekYielded = true;
                    return key + "/" + seekChild;
                }
                String ceiling = visitor.ceiling();
                while (true) {
                    if (page != null && position < page.size()) {
                        String child = page.get(position++);
                        String full = key + "/" + child;
                        if (ceiling != null && order(full, ceiling) >= 0) {
                            return null; // sorted siblings: nothing at or past the upper bound can be in range
                        }
                        return full;
                    }
                    if (page != null && page.size() < PAGE) {
                        return null; // the last page was short: this container is drained
                    }
                    // First page starts after the seek child (or at the beginning when no seek); each subsequent page
                    // resumes strictly after the previous page's last name - the ordered-paging cursor.
                    String startAfter = page == null
                            ? (seekChild != null ? seekChild : "")
                            : page.getLast();
                    List<String> next = new ArrayList<>();
                    store.page(key, startAfter, PAGE, next::add);
                    page = next;
                    position = 0;
                    if (page.isEmpty()) {
                        return null;
                    }
                }
            }
        }
    }
}
