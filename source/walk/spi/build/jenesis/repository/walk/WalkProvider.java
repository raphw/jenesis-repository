package build.jenesis.repository.walk;

import build.jenesis.repository.store.Features;

import module java.base;

/**
 * A named factory for the shared {@link ArtifactWalk}, discovered at runtime with {@link ServiceLoader} - the API is
 * an SPI kept separate from its implementation, so the enumeration strategy can change without breaking a consumer.
 * An exclusive SPI: {@code jenesis.repository.walk=<name>} selects one implementation by name; nothing set takes the
 * first enabled implementation in discovery order (the {@code store} reference implementation when its module is on
 * the graph). Each provider reads its own settings through the {@code config} lookup (a property accessor returning
 * {@code null} when unset - {@code jenesis.walk.checkpoint}, {@code jenesis.walk.segments}, ... for the reference
 * implementation). With no module installed {@link #resolve} is empty and {@link #installed()} is the capability
 * signal: every walk-riding surface then degrades gracefully - nothing enumerates, and the console / capabilities
 * say so - exactly like retention with no retention provider.
 */
public interface WalkProvider {

    /** The implementation name this provider answers to, e.g. {@code store}. */
    String name();

    /** Build the walk, reading settings through {@code config}; empty when configured off. */
    Optional<ArtifactWalk> create(UnaryOperator<String> config);

    /** The config keys this implementation cannot run without; empty (the default) for one that needs nothing. A
     *  provider whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** Whether an enabled walk implementation is installed - the capability signal a console or a walk-riding
     *  maintenance surface gates on; without one nothing ever enumerates. */
    static boolean installed() {
        for (WalkProvider provider : ServiceLoader.load(WalkProvider.class)) {
            if (Features.enabled(provider.name())) {
                return true;
            }
        }
        return false;
    }

    /** The first enabled walk discovered via {@link ServiceLoader} (an exclusive SPI: an explicit
     *  {@code jenesis.repository.walk=<name>} selects one by name, a {@code jenesis.repository.<name>=false} skips
     *  one, {@link Features}), or empty when none answers - the degrade-gracefully signal, never {@code null}. */
    static Optional<ArtifactWalk> resolve(UnaryOperator<String> config) {
        Optional<String> selection = Features.selection("walk");
        for (WalkProvider provider : ServiceLoader.load(WalkProvider.class)) {
            if (selection.isPresent()
                    ? !provider.name().equalsIgnoreCase(selection.get())
                    : !Features.active(provider.name(), provider.requiredConfig())) {
                continue;
            }
            Optional<ArtifactWalk> walk = provider.create(config);
            if (walk.isPresent()) {
                return walk;
            }
        }
        return Optional.empty();
    }
}
