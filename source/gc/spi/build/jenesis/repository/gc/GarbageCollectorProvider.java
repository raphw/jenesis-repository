package build.jenesis.repository.gc;

import build.jenesis.repository.store.Features;

import module java.base;

/**
 * A named factory for a {@link GarbageCollector}, discovered at runtime with {@link ServiceLoader} - the API is an
 * SPI kept separate from its implementation, so the reclamation strategy can change without breaking a caller. An
 * exclusive SPI: {@code jenesis.repository.gc=<name>} selects one implementation by name; nothing set takes the
 * first enabled implementation in discovery order (the {@code mark-sweep} reference implementation when its module
 * is on the graph). Each provider reads its own settings through the {@code config} lookup (a property accessor
 * returning {@code null} when unset - {@code jenesis.gc.*}). With no module installed {@link #resolve} is empty
 * and {@link #installed()} is the capability signal: <b>nothing is ever reclaimed</b> and the capability surfaces
 * say garbage collection is off - the no-op default, because deleting data is never something a deployment gets
 * without opting in.
 */
public interface GarbageCollectorProvider {

    /** The implementation name this provider answers to, e.g. {@code mark-sweep}. */
    String name();

    /** Build the collector, reading settings through {@code config}; empty when configured off or when a
     *  capability it rides (the shared artifact walk) is itself absent - either way the caller reclaims nothing. */
    Optional<GarbageCollector> create(UnaryOperator<String> config);

    /** The config keys this implementation cannot run without; empty (the default) for one that needs nothing. A
     *  provider whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** Whether an enabled garbage collector is installed - the capability signal a console or a maintenance
     *  surface gates on; without one nothing is ever reclaimed. */
    static boolean installed() {
        for (GarbageCollectorProvider provider : ServiceLoader.load(GarbageCollectorProvider.class)) {
            if (Features.enabled(provider.name())) {
                return true;
            }
        }
        return false;
    }

    /** The first enabled collector discovered via {@link ServiceLoader} (an exclusive SPI: an explicit
     *  {@code jenesis.repository.gc=<name>} selects one by name, a {@code jenesis.repository.<name>=false} skips
     *  one, {@link Features}), or empty when none answers - the no-op default, never {@code null}. */
    static Optional<GarbageCollector> resolve(UnaryOperator<String> config) {
        Optional<String> selection = Features.selection("gc");
        for (GarbageCollectorProvider provider : ServiceLoader.load(GarbageCollectorProvider.class)) {
            if (selection.isPresent()
                    ? !provider.name().equalsIgnoreCase(selection.get())
                    : !Features.active(provider.name(), provider.requiredConfig())) {
                continue;
            }
            Optional<GarbageCollector> collector = provider.create(config);
            if (collector.isPresent()) {
                return collector;
            }
        }
        return Optional.empty();
    }
}
