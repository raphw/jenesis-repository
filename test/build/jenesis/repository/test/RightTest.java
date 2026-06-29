package build.jenesis.repository.test;

import build.jenesis.repository.store.Right;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grant-token grammar of {@link Right}: an exact {@code <surface>:<verb>} confers only itself, a per-surface
 * {@code <surface>:*} confers every verb of that surface only, and {@code *} confers every privilege - the way a
 * key is handed the full set in one token. Unknown tokens confer nothing.
 */
class RightTest {

    @Test
    void an_exact_token_confers_only_itself() {
        assertThat(Right.REPOSITORY_WRITE.grantedBy("repository:write")).isTrue();
        assertThat(Right.REPOSITORY_WRITE.grantedBy("repository:read")).isFalse();
        assertThat(Right.REPOSITORY_WRITE.grantedBy("cache:write")).isFalse();
    }

    @Test
    void a_surface_wildcard_confers_every_verb_of_that_surface_only() {
        assertThat(Right.REPOSITORY_READ.grantedBy("repository:*")).isTrue();
        assertThat(Right.REPOSITORY_WRITE.grantedBy("repository:*")).isTrue();
        assertThat(Right.CACHE_WRITE.grantedBy("repository:*")).isFalse();
    }

    @Test
    void the_all_privileges_token_confers_every_right() {
        for (Right right : Right.values()) {
            assertThat(right.grantedBy("*")).as("right %s", right).isTrue();
        }
    }

    @Test
    void an_unknown_token_confers_nothing() {
        assertThat(Right.REPOSITORY_READ.grantedBy("repository:delete")).isFalse();
        assertThat(Right.REPOSITORY_READ.grantedBy("read")).isFalse();
        assertThat(Right.REPOSITORY_READ.grantedBy("nonsense")).isFalse();
    }
}
