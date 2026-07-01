package build.jenesis.repository.store;

import java.util.ServiceLoader;
import java.util.function.UnaryOperator;

/**
 * A named factory for an {@link ArtifactStore} backend, discovered at runtime with {@link ServiceLoader}.
 * The server selects one by name (the {@code JENESIS_STORE} setting, default {@code filesystem}); each
 * provider reads its own configuration through the {@code config} lookup, staying free of any framework
 * dependency. An optional cloud backend (S3 / Azure Blob) is added to the module graph at deploy time and
 * bound here through {@code provides}, with no compile-time dependency from the server.
 */
public interface ArtifactStoreProvider {

    /** The backend name this provider answers to, e.g. {@code filesystem}, {@code s3}, {@code azure-blob}. */
    String name();

    /** Build the backend, reading configuration through {@code config} (a property/env lookup returning null if unset). */
    ArtifactStore create(UnaryOperator<String> config);

    /** Resolve the named backend via {@link ServiceLoader}, falling back to the bundled {@code filesystem} backend. */
    static ArtifactStore resolve(String name, UnaryOperator<String> config) {
        ArtifactStoreProvider chosen = null, filesystem = null;
        for (ArtifactStoreProvider provider : ServiceLoader.load(ArtifactStoreProvider.class)) {
            if (provider.name().equalsIgnoreCase(name)) {
                chosen = provider;
            }
            if (provider.name().equalsIgnoreCase("filesystem")) {
                filesystem = provider;
            }
        }
        ArtifactStoreProvider selected = chosen != null ? chosen : filesystem;
        if (selected == null) {
            throw new IllegalStateException("No artifact store provider answers to '" + name + "', and no filesystem fallback is present");
        }
        return selected.create(config);
    }
}
