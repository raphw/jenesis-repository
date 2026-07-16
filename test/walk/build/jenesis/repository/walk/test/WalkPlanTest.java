package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static segment plan: a flat content-addressed namespace (all long lowercase hex, the {@code blobs/} shape) is
 * cut by leading hex byte without listing its children, child packing meets the segment target on ordinary roots
 * descending a level where the fan-out is too small, and an over-cap root with no uniform naming conservatively
 * stays one segment - in every shape the pass still visits each key exactly once with contiguous, gap-free ranges.
 */
class WalkPlanTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static String hash(int index) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(("blob-" + index).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void a_flat_hex_namespace_is_cut_by_leading_hex_byte_and_walked_exactly_once() throws IOException {
        ArtifactStore store = store();
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            String key = "blobs/" + hash(index);
            keys.add(key);
            store.writeVersioned(key, new byte[0], null);
        }
        StoreArtifactWalk walk = new StoreArtifactWalk(50, 8, Duration.ofMinutes(10), clock);
        List<String> visited = new ArrayList<>();
        WalkPass pass = walk.walk(store, "test", List.of("blobs"), visited::add);
        assertThat(pass.complete()).isTrue();
        assertThat(pass.segments()).as("the hex cut meets the segment target").isEqualTo(8);
        assertThat(visited).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(visited).containsExactlyElementsOf(keys.stream().sorted().toList());
        List<WalkSegment> segments = walk.segments(store, "test");
        assertThat(segments).hasSize(8);
        assertThat(segments.getFirst().from()).isNull();
        assertThat(segments.getLast().to()).isNull();
        for (int index = 1; index < segments.size(); index++) {
            assertThat(segments.get(index).from()).as("ranges are contiguous, no gap and no overlap")
                    .isEqualTo(segments.get(index - 1).to());
            assertThat(segments.get(index).state()).isEqualTo(WalkSegment.State.DONE);
        }
    }

    @Test
    void an_over_cap_root_without_uniform_naming_stays_one_conservative_segment() throws IOException {
        ArtifactStore store = store();
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            String key = String.format("publish/dir-%03d", index);
            keys.add(key);
            store.writeVersioned(key, new byte[0], null);
        }
        StoreArtifactWalk walk = new StoreArtifactWalk(10, 8, Duration.ofMinutes(10), clock);
        List<String> visited = new ArrayList<>();
        WalkPass pass = walk.walk(store, "test", List.of("publish"), visited::add);
        assertThat(pass.complete()).isTrue();
        assertThat(pass.segments()).isEqualTo(1);
        assertThat(visited).containsExactlyElementsOf(keys.stream().sorted().toList());
    }

    @Test
    void a_root_with_few_children_descends_a_level_to_meet_the_target() throws IOException {
        ArtifactStore store = store();
        List<String> keys = new ArrayList<>();
        for (String group : List.of("com", "net", "org")) {
            for (int index = 0; index < 6; index++) {
                String key = "publish/" + group + "/artifact-" + index + "/1.0/file.jar";
                keys.add(key);
                store.writeVersioned(key, new byte[0], null);
            }
        }
        StoreArtifactWalk walk = new StoreArtifactWalk(4, 6, Duration.ofMinutes(10), clock);
        List<String> visited = new ArrayList<>();
        WalkPass pass = walk.walk(store, "test", List.of("publish"), visited::add);
        assertThat(pass.complete()).isTrue();
        assertThat(pass.segments()).as("grandchild cuts push past the flat child count").isGreaterThan(1);
        assertThat(visited).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(visited).containsExactlyElementsOf(keys.stream().sorted().toList());
    }
}
