package build.jenesis.repository.store;

import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A named factory for an {@link ArtifactStore} backend, discovered at runtime with {@link ServiceLoader}.
 * The store is an <em>exclusive</em> SPI in the {@link Features} convention: the server selects one by name
 * (the {@code jenesis.repository.store} setting, default {@code filesystem} - the most universally applicable
 * backend); each provider reads its own configuration through the {@code config} lookup, staying free of any
 * framework dependency. An optional cloud backend (S3 / GCS / Azure Blob) is added to the module graph at deploy
 * time and bound here through {@code provides}, with no compile-time dependency from the server. A selected
 * backend whose {@link #requiredConfig() required configuration} is unset fails loudly rather than self-disabling:
 * silently falling back to another store would serve and persist against the wrong backend.
 */
public interface ArtifactStoreProvider {

    /** The backend name this provider answers to, e.g. {@code filesystem}, {@code s3}, {@code gcs}, {@code azure-blob}. */
    String name();

    /** Build the backend, reading configuration through {@code config} (a property/env lookup returning null if unset). */
    ArtifactStore create(UnaryOperator<String> config);

    /** The config keys this backend cannot run without (a bucket, a connection string) - empty (the default) for a
     *  backend that needs nothing. A credential with an ambient fallback (an instance role, a default chain) is not
     *  required config. {@link #resolve} checks these up front so a misconfigured selection fails with one message
     *  naming every missing key. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

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
        List<String> missing = Features.missing(selected.requiredConfig(), config);
        if (!missing.isEmpty()) {
            // The store is the one exclusive SPI that must not self-disable: fail loudly, naming what is unset.
            throw new IllegalStateException("The " + selected.name() + " artifact store backend is selected but its"
                    + " required configuration is missing: " + String.join(", ", missing));
        }
        return selected.create(config);
    }
}
