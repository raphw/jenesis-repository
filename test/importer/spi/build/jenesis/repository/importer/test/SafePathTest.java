package build.jenesis.repository.importer.test;

import build.jenesis.repository.importer.ImportSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The uniform listing-path guard every source applies before reporting an asset: a path a foreign listing hands over
 * becomes a store write on the import's write half, so it must be relative with no empty, dot or dot-dot segment and
 * no backslash - anything else is skipped by the source rather than aimed at the store.
 */
class SafePathTest {

    @Test
    void ordinary_layout_paths_are_safe() {
        assertThat(ImportSource.safePath("org/example/lib/1.0/lib-1.0.jar")).isTrue();
        assertThat(ImportSource.safePath("@scope/pkg/-/pkg-1.0.0.tgz")).isTrue();
        assertThat(ImportSource.safePath("a name with spaces.jar")).isTrue();
        assertThat(ImportSource.safePath("v2/app/manifests/1.0")).isTrue();
    }

    @Test
    void traversal_absolute_and_degenerate_paths_are_not() {
        assertThat(ImportSource.safePath(null)).isFalse();
        assertThat(ImportSource.safePath("")).isFalse();
        assertThat(ImportSource.safePath("/absolute/path.jar")).isFalse();
        assertThat(ImportSource.safePath("trailing/slash/")).isFalse();
        assertThat(ImportSource.safePath("double//slash.jar")).isFalse();
        assertThat(ImportSource.safePath("..")).isFalse();
        assertThat(ImportSource.safePath("../../auth/keys")).isFalse();
        assertThat(ImportSource.safePath("org/../../../etc/passwd")).isFalse();
        assertThat(ImportSource.safePath("org/./lib.jar")).isFalse();
        assertThat(ImportSource.safePath("org\\example\\lib.jar")).isFalse();
    }
}
