package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishedAssets;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The {@code publish/} walk must be race-tolerant: a pointer is read twice per emitted leaf - once by the servable
 * screen ({@code ServableNames.state}, one {@code readVersioned} plus a {@code blobs/<hash>} stat) and once by the
 * follow-up read that turns the screened leaf into an {@link PublishedAssets.Entry}. On an object store (S3 / GCS /
 * Azure) each of those is a network round trip, so a concurrent unpublish / evict / {@code DELETE} can remove the
 * pointer in the window <em>between</em> the screen and the follow-up read.
 *
 * <p>A pointer that vanished in that window must be SKIPPED and the walk must CONTINUE, exactly the pre-seam
 * "skip and continue": aborting the whole enumeration would truncate the console's in-flight {@code /assets} NDJSON
 * export and 500 the server's {@code /api/assets} catalogue. This pins that a vanished-after-screen leaf is dropped
 * while every surviving sibling is still emitted; reverting the skip (throwing on the empty follow-up read) fails it,
 * which is what makes the guard load-bearing.
 */
class PublishedAssetsRaceTest {

    @Test
    void a_pointer_that_vanishes_after_the_screen_is_skipped_and_the_walk_continues() {
        // Three published leaves; the middle one's pointer disappears after it has screened SERVABLE but before the
        // follow-up read - the store hands the pointer back on the screen read and reports it gone on the next read.
        RacingStore store = new RacingStore(List.of("a", "b", "c"), "b");
        PublishedAssets assets = new PublishedAssets(store);

        List<String> emitted = new ArrayList<>();
        assertThatCode(() -> assets.walk(null, Integer.MAX_VALUE, entry -> emitted.add(entry.path())))
                .as("a pointer vanishing between the screen and the follow-up read must be skipped, not abort the walk")
                .doesNotThrowAnyException();

        assertThat(emitted)
                .as("the vanished pointer is skipped and every surviving sibling is still emitted")
                .containsExactly("/a", "/c");
        assertThat(store.vanishingReads)
                .as("the guard is only exercised once the vanished pointer has been read a second time (screen + emit)")
                .isGreaterThanOrEqualTo(2);
    }

    /** A flat {@code publish/} tree of single-segment leaves. Every leaf's pointer resolves to a present blob, EXCEPT
     *  the one named {@code vanishing}: its pointer is handed back on the first {@code readVersioned} (the servable
     *  screen) and reported gone on every read after (the concurrent unpublish landing in the screen-to-emit window).
     *  The blob it named still exists, so the failure mode under test is purely the pointer racing away, not a torn
     *  {@code blobs/<hash>}. */
    private static final class RacingStore implements ArtifactStore {

        private static final String HASH = "0".repeat(64);

        private final List<String> leaves;
        private final String vanishing;
        private int vanishingReads;

        RacingStore(List<String> leaves, String vanishing) {
            this.leaves = leaves;
            this.vanishing = vanishing;
        }

        @Override
        public List<String> list(String prefix) {
            return prefix.equals("publish") ? leaves : List.of(); // each leaf is childless
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            String leaf = key.substring("publish/".length());
            if (leaf.equals(vanishing) && vanishingReads++ >= 1) {
                return Optional.empty(); // unpublished after the screen read - gone on every read that follows
            }
            return Optional.of(new Versioned(HASH.getBytes(StandardCharsets.UTF_8), new Object()));
        }

        @Override
        public boolean exists(String key) {
            return key.equals("blobs/" + HASH); // the blob is present; only the pointer races away
        }

        @Override
        public long size(String key) {
            return key.equals("blobs/" + HASH) ? 7L : -1L;
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
