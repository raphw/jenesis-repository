package build.jenesis.repository;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The module-name to Maven-coordinate index that bridges the two layouts. A module published under
 * {@code /module/<name>} registers {@code <name> -> <groupId>:<artifactId>} here, so a Jenesis client
 * resolving by module name and a Maven client resolving by coordinate reach the same artifact. Backed by a
 * small object in the store ({@code index/module/<name>}), written with the store's compare-and-set.
 */
public final class ModuleBridge {

    private final ArtifactStore store;

    public ModuleBridge(ArtifactStore store) {
        this.store = store;
    }

    /** The Maven coordinate ({@code groupId:artifactId}) registered for a module name, or null if unknown. */
    public String coordinate(String moduleName) {
        try {
            return store.readVersioned("index/module/" + moduleName)
                    .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim())
                    .orElse(null);
        } catch (IOException _) {
            return null;
        }
    }

    /** Register a module-name to coordinate binding (called on a module publish). */
    public void register(String moduleName, String coordinate) throws IOException {
        Object token = store.readVersioned("index/module/" + moduleName)
                .map(ArtifactStore.Versioned::token)
                .orElse(null);
        store.writeVersioned("index/module/" + moduleName, coordinate.getBytes(StandardCharsets.UTF_8), token);
    }
}
