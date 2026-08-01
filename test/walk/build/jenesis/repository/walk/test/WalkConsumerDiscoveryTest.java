package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkConsumer;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WalkConsumer#discovered()} - the {@link java.util.ServiceLoader} enumeration the shared rebuild pass drives
 * from one place - lists every enabled consumer and skips a disabled one under the shared {@code
 * jenesis.repository.<name>} feature convention: nothing set means enabled, only an explicit {@code false} disables.
 * The {@link DiscoverableWalkConsumer} registered by this test module stands in for a shipped consumer.
 */
class WalkConsumerDiscoveryTest {

    @AfterEach
    void restoreFeatures() {
        Features.reset();
    }

    @Test
    void a_registered_consumer_is_discovered_when_enabled() {
        Features.reset(); // default lookup: the feature is unset, so enabled
        assertThat(WalkConsumer.discovered())
                .as("a ServiceLoader-registered consumer is enumerated when its feature is unset")
                .anySatisfy(consumer -> assertThat(consumer).isInstanceOf(DiscoverableWalkConsumer.class))
                .extracting(WalkConsumer::name)
                .contains(DiscoverableWalkConsumer.NAME);
    }

    @Test
    void a_disabled_consumer_is_skipped_at_discovery() {
        Features.configure(key -> ("jenesis.repository." + DiscoverableWalkConsumer.NAME).equals(key) ? "false" : null);

        assertThat(WalkConsumer.discovered())
                .as("an explicit jenesis.repository.<name>=false drops the consumer from the enumeration")
                .noneSatisfy(consumer -> assertThat(consumer).isInstanceOf(DiscoverableWalkConsumer.class));
    }
}
