package build.jenesis.repository.usage;

import java.util.Optional;
import java.util.function.UnaryOperator;
import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.server.KeyUsageTracker;
import build.jenesis.repository.server.KeyUsageTrackerProvider;

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
