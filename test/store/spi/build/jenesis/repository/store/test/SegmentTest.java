package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The traversal-free scope backstop {@link ArtifactStore#segment}: the defence-in-depth check every backend's
 * {@code scope} runs its argument through, so a store can never silently escape or misplace its subspace on a bad
 * segment. {@link FilesystemArtifactStoreTest} covers the {@code ..} / {@code a/b} / empty rejections through the
 * filesystem store; this pins the three the store suites never reach - the single dot {@code .}, the Windows-style
 * backslash separator {@code \}, and a {@code null} segment - and confirms a plain hidden-subspace name is still
 * allowed, both directly on the static method and through a real {@code scope(...)} call so the backstop is exercised
 * on the path a caller actually takes.
 */
class SegmentTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void the_backstop_rejects_a_single_dot_a_backslash_and_null() {
        ArtifactStore store = store();
        assertThatThrownBy(() -> store.scope("."))
                .as("a single dot resolves to the current directory and never scopes the store")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.scope("a\\b"))
                .as("a backslash path separator never scopes the store")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.scope(null))
                .as("a null segment never scopes the store")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_static_backstop_rejects_every_traversal_form_and_admits_a_plain_or_hidden_name() {
        for (String bad : new String[] {null, "", ".", "..", "a/b", "a\\b"}) {
            assertThatThrownBy(() -> ArtifactStore.segment(bad))
                    .as("segment(%s) is not a traversal-free scope name", String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ArtifactStore.segment("acme")).as("a plain name is returned unchanged").isEqualTo("acme");
        assertThat(ArtifactStore.segment(".tests"))
                .as("a hidden internal space (.tests / .scans) is not a traversal and is allowed").isEqualTo(".tests");
    }

    @Test
    void a_hidden_subspace_name_still_scopes_as_a_subdirectory() throws IOException {
        ArtifactStore store = store();
        store.scope(".scans").write("blobs/x", new ByteArrayInputStream("internal".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.exists(".scans/blobs/x"))
                .as("a hidden name passes the backstop and scopes as a real subdirectory").isTrue();
    }
}
