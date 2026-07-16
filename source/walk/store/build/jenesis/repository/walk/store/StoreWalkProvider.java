package build.jenesis.repository.walk.store;

import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkProvider;

import module java.base;

/**
 * Provides the {@link StoreArtifactWalk} reference implementation as {@code store} - the default selection when no
 * {@code jenesis.repository.walk} names another. Settings, read through the config lookup:
 * {@code jenesis.walk.checkpoint} items per cursor commit (default 1000), {@code jenesis.walk.segments} target
 * segments per pass (default 32), {@code jenesis.walk.ttl} claim lease seconds (default 900 - a checkpoint stride
 * must renew within it, so scale the two together). A malformed value fails loudly rather than walking with a
 * silently-wrong stride.
 */
public final class StoreWalkProvider implements WalkProvider {

    @Override
    public String name() {
        return "store";
    }

    @Override
    public Optional<ArtifactWalk> create(UnaryOperator<String> config) {
        return Optional.of(new StoreArtifactWalk(
                integer(config, "jenesis.walk.checkpoint", 1000),
                integer(config, "jenesis.walk.segments", 32),
                Duration.ofSeconds(integer(config, "jenesis.walk.ttl", 900)),
                Clock.systemUTC()));
    }

    private static int integer(UnaryOperator<String> config, String key, int fallback) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not an integer: " + key + "=" + value, e);
        }
    }
}
