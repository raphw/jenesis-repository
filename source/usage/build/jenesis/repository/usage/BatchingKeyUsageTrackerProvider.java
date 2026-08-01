package build.jenesis.repository.usage;

import module java.base;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.spi.KeyUsageTrackerProvider;

/**
 * Discovers the batching usage tracker: recording is off unless {@code track-key-usage} switches it on, and a
 * disabled tracker still stands (its worker reports as off) so a health surface distinguishes "installed but off"
 * from a dead worker.
 */
public final class BatchingKeyUsageTrackerProvider implements KeyUsageTrackerProvider {

    @Override
    public String name() {
        return "batching";
    }

    @Override
    public Optional<KeyUsageTracker> create(Authorization authorization, UnaryOperator<String> config) {
        return Optional.of(new BatchingKeyUsageTracker(authorization,
                Boolean.parseBoolean(config.apply("track-key-usage"))));
    }
}
