package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishedAssets;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The {@code publish/} pointer-tree walk must survive a pathologically deep tree. A published request-path's depth is
 * client-controlled (the deploy controller rejects {@code ..} but not depth), so a deeply nested deploy path used to
 * drive {@link PublishedAssets#walk} into an unbounded recursion that overflowed the stack with a
 * {@link StackOverflowError} - an {@link Error} that slips past the caller's {@code IOException}/{@code RuntimeException}
 * handlers and, because the pointer is durable, wedges the {@code /api/assets} catalogue and the console asset export
 * until the pointer is removed. The walk is now an explicit work-list, so an arbitrarily deep chain is descended
 * without recursion. This pins that a {@value #DEPTH}-deep chain - far past any stack the old recursion could hold -
 * walks to completion.
 */
class PublishedAssetsDepthTest {

    private static final int DEPTH = 20_000;

    @Test
    void a_pathologically_deep_pointer_chain_walks_without_overflowing_the_stack() {
        DeepChainStore store = new DeepChainStore();
        PublishedAssets assets = new PublishedAssets(store);

        assertThatCode(() -> assets.walk(null, Integer.MAX_VALUE, entry -> { }))
                .as("a %d-deep pointer chain must not overflow the stack (an iterative walk, not recursion)", DEPTH)
                .doesNotThrowAnyException();
        assertThat(store.maxDepth)
                .as("the walk actually descended the full chain rather than stopping short")
                .isEqualTo(DEPTH);
    }

    /** A store whose {@code publish/} subtree is a single chain {@code publish/a/a/.../a} exactly {@value DEPTH} levels
     *  deep: each node has one child {@code a} until the leaf, which has none. Nothing else is stored, so the leaf's
     *  pointer resolves to nothing and no entry is emitted - the walk's descent is what the deep chain stresses. */
    private static final class DeepChainStore implements ArtifactStore {

        private int maxDepth;

        @Override
        public List<String> list(String prefix) {
            // Depth = number of "a" segments already below "publish": list("publish") is depth 0, "publish/a" depth 1...
            int depth = prefix.equals("publish") ? 0 : (int) prefix.chars().filter(c -> c == '/').count();
            maxDepth = Math.max(maxDepth, depth);
            return depth < DEPTH ? List.of("a") : List.of();
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            return Optional.empty();          // no pointer resolves - located() returns empty, the leaf is skipped
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public long size(String key) {
            return -1L;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public void read(String key, OutputStream out) {
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void write(String key, InputStream in) {
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            return false;
        }
    }
}
