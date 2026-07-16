package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.store.Features;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ServiceLoader resolution and its no-op default: the {@code mark-sweep} implementation answers when installed
 * and enabled; an explicit selection of another name, a disabled feature, or a missing walk all resolve to empty -
 * and empty means <em>nothing is ever reclaimed</em>, the SPI's default for the one unrecoverable operation.
 */
class GcProviderTest {

    @Test
    void the_provider_resolves_the_mark_sweep_collector_and_honours_the_exclusive_selection() {
        assertThat(GarbageCollectorProvider.installed()).isTrue();
        assertThat(GarbageCollectorProvider.resolve(key -> null)).isPresent();
        Features.configure(key -> "jenesis.repository.gc".equals(key) ? "other" : null);
        try {
            assertThat(GarbageCollectorProvider.resolve(key -> null))
                    .as("an explicit selection of another implementation is the no-op default").isEmpty();
        } finally {
            Features.reset();
        }
    }

    @Test
    void a_disabled_feature_resolves_to_the_no_op_default() {
        Features.configure(key -> "jenesis.repository.mark-sweep".equals(key) ? "false" : null);
        try {
            assertThat(GarbageCollectorProvider.resolve(key -> null)).isEmpty();
        } finally {
            Features.reset();
        }
    }

    @Test
    void without_a_walk_nothing_ever_collects() {
        // The collector rides the shared walk; with no walk implementation answering there is no enumeration to
        // ride, and the deployment degrades to no garbage collection rather than a hand-rolled listing loop.
        Features.configure(key -> "jenesis.repository.walk".equals(key) ? "absent" : null);
        try {
            assertThat(GarbageCollectorProvider.resolve(key -> null)).isEmpty();
        } finally {
            Features.reset();
        }
    }

    @Test
    void garbage_settings_fail_loudly_instead_of_collecting_with_a_wrong_stride() {
        assertThat(GarbageCollectorProvider.resolve(
                key -> "jenesis.gc.stride".equals(key) ? "512" : null)).isPresent();
        assertThatThrownBy(() -> GarbageCollectorProvider.resolve(
                key -> "jenesis.gc.stride".equals(key) ? "many" : null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GarbageCollectorProvider.resolve(
                key -> "jenesis.gc.stride".equals(key) ? "0" : null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
