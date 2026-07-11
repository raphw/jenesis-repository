package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The config-driven SPI enable/disable convention: a feature toggle ({@code jenesis.repository.<feature>}) is on
 * unless explicitly {@code false}, an exclusive SPI reads its selection key, a feature whose required config keys
 * are unset self-disables, and the default lookup answers from system properties - so the identical keys work with
 * and without an application shell. The exclusive store backend is the deliberate exception to self-disabling: a
 * selected backend with missing required config fails loudly, because silently falling back to another store would
 * persist against the wrong backend.
 */
class FeaturesTest {

    @AfterEach
    void restore() {
        Features.reset();
    }

    @Test
    void a_feature_is_enabled_unless_configured_false() {
        Features.configure(key -> null);
        assertThat(Features.enabled("anything")).isTrue();
        Features.configure(Map.of("jenesis.repository.anything", "false")::get);
        assertThat(Features.enabled("anything")).isFalse();
    }

    @Test
    void only_an_explicit_false_disables() {
        Features.configure(Map.of(
                "jenesis.repository.on", "true",
                "jenesis.repository.blank", "",
                "jenesis.repository.shouting", "FALSE")::get);
        assertThat(Features.enabled("on")).isTrue();
        assertThat(Features.enabled("blank")).isTrue();
        assertThat(Features.enabled("shouting")).isFalse();
    }

    @Test
    void an_exclusive_selection_reads_its_spi_key() {
        Features.configure(Map.of("jenesis.repository.token-exchange", " oidc ")::get);
        assertThat(Features.selection("token-exchange")).contains("oidc");
        assertThat(Features.selection("store")).isEmpty();
    }

    @Test
    void a_feature_missing_required_config_self_disables() {
        Features.configure(Map.of("some-credential", "value")::get);
        assertThat(Features.active("fed", Set.of("some-credential"))).isTrue();
        assertThat(Features.active("starved", Set.of("some-credential", "other-credential"))).isFalse();
    }

    @Test
    void a_disabled_feature_is_inactive_regardless_of_required_config() {
        Features.configure(Map.of("jenesis.repository.off", "false")::get);
        assertThat(Features.active("off", Set.of())).isFalse();
    }

    @Test
    void system_properties_answer_the_default_lookup() {
        System.setProperty("jenesis.repository.sys-prop-feature", "false");
        try {
            Features.reset();
            assertThat(Features.enabled("sys-prop-feature")).isFalse();
            assertThat(Features.enabled("some-other-feature")).isTrue();
        } finally {
            System.clearProperty("jenesis.repository.sys-prop-feature");
        }
    }

    @Test
    void a_selected_store_backend_with_missing_required_config_fails_loudly() {
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve("needy", key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needy")
                .hasMessageContaining("NEEDY_BUCKET");
    }
}
