package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The global key-shape cap {@link ArtifactStore#key}: the write-path backstop that makes an attacker-planted key
 * depth or length unrepresentable at the source, so any recursive descent anywhere is bounded even if hand-rolled
 * naively. It caps a stored key at {@link ArtifactStore#MAX_SEGMENTS} ({@value ArtifactStore#MAX_SEGMENTS})
 * {@code '/'}-separated segments and {@link ArtifactStore#MAX_KEY_BYTES} ({@value ArtifactStore#MAX_KEY_BYTES})
 * UTF-8 bytes, rejecting a violation with the same {@link IllegalArgumentException} a traversal violation raises.
 *
 * <p>This pins the static screen on both boundaries (accepted at the cap, rejected one past it, in both dimensions,
 * with the byte cap counting UTF-8 bytes rather than {@code char}s) and confirms a representative backend - the
 * filesystem store every {@code scope} routes through - enforces it on the {@code write} and {@code writeVersioned}
 * paths, so an over-shaped key is refused before it ever resolves to a path.
 */
class KeyShapeCapTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    /** A key of exactly {@code segments} single-character {@code '/'}-separated segments ({@code a/a/.../a}). */
    private static String segments(int segments) {
        return String.join("/", Collections.nCopies(segments, "a"));
    }

    @Test
    void the_static_cap_admits_the_boundary_and_rejects_one_segment_past_it() {
        assertThat(ArtifactStore.key(segments(ArtifactStore.MAX_SEGMENTS)))
                .as("a key at exactly the %d-segment cap is accepted", ArtifactStore.MAX_SEGMENTS)
                .isEqualTo(segments(ArtifactStore.MAX_SEGMENTS));
        assertThatThrownBy(() -> ArtifactStore.key(segments(ArtifactStore.MAX_SEGMENTS + 1)))
                .as("a key one segment past the cap (%d segments) is rejected", ArtifactStore.MAX_SEGMENTS + 1)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_static_cap_admits_the_byte_boundary_and_rejects_one_byte_past_it() {
        // One long segment, so only the byte cap (not the segment cap) is in play.
        String atCap = "a".repeat(ArtifactStore.MAX_KEY_BYTES);
        assertThat(ArtifactStore.key(atCap))
                .as("a key at exactly the %d-byte cap is accepted", ArtifactStore.MAX_KEY_BYTES)
                .isEqualTo(atCap);
        assertThatThrownBy(() -> ArtifactStore.key("a".repeat(ArtifactStore.MAX_KEY_BYTES + 1)))
                .as("a key one byte past the cap is rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_byte_cap_counts_utf8_bytes_not_chars() {
        // 2000 three-byte characters = 6000 UTF-8 bytes but only 2000 chars: under the char-count cap, over the byte
        // cap, so a cap that measured length() would wrongly admit it.
        String multibyte = "€".repeat(2000); // euro sign, 3 bytes each
        assertThat(multibyte.length()).isLessThan(ArtifactStore.MAX_KEY_BYTES);
        assertThat(multibyte.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(ArtifactStore.MAX_KEY_BYTES);
        assertThatThrownBy(() -> ArtifactStore.key(multibyte))
                .as("the cap measures UTF-8 bytes, so a multibyte key over the byte cap is rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_key_is_rejected() {
        assertThatThrownBy(() -> ArtifactStore.key(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_backend_rejects_an_over_deep_key_on_both_write_paths() {
        ArtifactStore store = store();
        String tooDeep = segments(ArtifactStore.MAX_SEGMENTS + 1);
        assertThatThrownBy(() -> store.write(tooDeep, new ByteArrayInputStream(new byte[0])))
                .as("write refuses a 65-segment key before it resolves to a path")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.writeVersioned(tooDeep, new byte[0], null))
                .as("writeVersioned refuses a 65-segment key before it resolves to a path")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_backend_rejects_an_over_long_key_on_both_write_paths() {
        ArtifactStore store = store();
        // 64 segments (within the segment cap) of 100 chars each = ~6.4k bytes: only the byte cap trips, and it trips
        // before any filesystem resolution is attempted.
        String tooLong = String.join("/", Collections.nCopies(ArtifactStore.MAX_SEGMENTS, "a".repeat(100)));
        assertThat(tooLong.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(ArtifactStore.MAX_KEY_BYTES);
        assertThatThrownBy(() -> store.write(tooLong, new ByteArrayInputStream(new byte[0])))
                .as("write refuses an over-4096-byte key").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.writeVersioned(tooLong, new byte[0], null))
                .as("writeVersioned refuses an over-4096-byte key").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_backend_accepts_a_key_at_the_segment_cap() throws IOException {
        ArtifactStore store = store();
        String atCap = segments(ArtifactStore.MAX_SEGMENTS); // 64 single-char segments: FS-legal, at the cap
        assertThatCode(() -> store.writeVersioned(atCap, "in".getBytes(StandardCharsets.UTF_8), null))
                .as("a key at exactly the 64-segment cap is a legal write, not a rejection")
                .doesNotThrowAnyException();
        assertThat(store.exists(atCap)).as("the at-cap key actually landed").isTrue();
    }
}
