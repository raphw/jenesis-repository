package build.jenesis.repository.store;

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
        return new FilesystemArtifactStore(Path.of(root == null || root.isBlank() ? "/var/lib/jenesis-repository" : root));
    }
}
