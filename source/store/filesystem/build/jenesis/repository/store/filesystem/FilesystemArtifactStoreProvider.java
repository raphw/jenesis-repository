package build.jenesis.repository.store.filesystem;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import module java.base;

/** The {@code filesystem} provider: a store rooted at {@code JENESIS_STORE_ROOT} (default {@code /var/lib/jenesis-repository}). */
public final class FilesystemArtifactStoreProvider implements ArtifactStoreProvider {

    @Override
    public String name() {
        return "filesystem";
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String root = config.apply("JENESIS_STORE_ROOT");
        Path path = Path.of(root == null || root.isBlank() ? "/var/lib/jenesis-repository" : root);
        try {
            // Create the store root owner-only (rwx------) up front, so the top-level container is never left
            // world-readable at the process umask; a root that cannot be created is a fail-fast, not a store
            // that silently lands blobs somewhere unintended.
            OwnerOnly.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create filesystem store root " + path, e);
        }
        return new FilesystemArtifactStore(path);
    }
}
