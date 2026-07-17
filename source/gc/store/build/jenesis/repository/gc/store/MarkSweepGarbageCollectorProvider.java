package build.jenesis.repository.gc.store;

import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.walk.WalkProvider;

import module java.base;

/**
 * Provides the {@link MarkSweepGarbageCollector} as {@code mark-sweep} - the default selection when no
 * {@code jenesis.repository.gc} names another. It rides the shared artifact walk, so with no walk implementation
 * installed (or one configured off) it resolves to empty and the deployment simply has no garbage collection - the
 * SPI's no-op default, never a collector that enumerates its own way. Settings, read through the config lookup:
 * {@code jenesis.gc.stride} - the checkpoint stride of the collector's own walk passes (default 20000), which
 * bounds three things at once: the reference batch a mark buffers in memory, the re-work a crash costs, and how
 * often a segment claim is renewed (keep stride x per-item time well under {@code jenesis.walk.ttl}). A malformed
 * value fails loudly rather than collecting with a silently-wrong stride.
 *
 * <p>{@code jenesis.gc.grace} - an optional ISO-8601 wall-clock floor on the condemn-to-collect grace (default
 * {@code PT0S}, i.e. purely generation-based: condemn in one pass, collect in the next). Set it to guarantee a blob
 * carries its condemned marker for at least this long before deletion even when generations advance faster than the
 * collection interval - several nodes collecting, or a node re-collecting after a lease expiry. It only ever delays a
 * deletion, so it never reclaims a blob the generation gap would spare.
 */
public final class MarkSweepGarbageCollectorProvider implements GarbageCollectorProvider {

    @Override
    public String name() {
        return "mark-sweep";
    }

    @Override
    public Optional<GarbageCollector> create(UnaryOperator<String> config) {
        String stride = Integer.toString(integer(config, "jenesis.gc.stride", 20_000));
        Duration grace = duration(config, "jenesis.gc.grace");
        return WalkProvider.resolve(key ->
                        "jenesis.walk.checkpoint".equals(key) ? stride : config.apply(key))
                .map(walk -> new MarkSweepGarbageCollector(walk, grace));
    }

    private static Duration duration(UnaryOperator<String> config, String key) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        try {
            return Duration.parse(value.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not an ISO-8601 duration: " + key + "=" + value, e);
        }
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
