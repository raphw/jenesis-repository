package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.Publication;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis format's contribution to cross-publishing: when the Maven format publishes a modular jar, it hands the
 * module here to also give it a {@code /module/} view - the jar linked by module name and version (and by module name
 * alone, the latest) over the same content-addressed blob. Discovered by the Maven format through {@link ServiceLoader}
 * over the {@link ModuleView} contract the shared Java-layout module exports to just these two modules.
 */
public final class ModuleViewPublisher implements ModuleView {

    @Override
    public void publish(String moduleName, String version, byte[] jar, ArtifactStore store) throws IOException {
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(jar);
        publication.link("/module/" + moduleName + "/" + version + "/" + moduleName + ".jar", hash);
        publication.link("/module/" + moduleName + "/" + moduleName + ".jar", hash);
    }
}
