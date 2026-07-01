package build.jenesis.repository.format.java.bridge;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis-layout side of cross-publishing: provided by the Jenesis format, used by the Maven format. Given the
 * content {@code hash} the Maven format already stored a modular jar under, it points the jar's {@code /module/} view
 * at that same content-addressed blob, so a client resolving by module name reaches the artifact a Maven client
 * published by coordinate - a pointer, not a re-upload. This is not part of the public {@code RepositoryFormat} SPI -
 * it is a bridge exposed (through a qualified export from the shared Java-layout module) only between the Maven and
 * Jenesis layout modules, the only two formats that cross-publish.
 */
public interface ModuleView {

    void publish(String moduleName, String version, String hash, ArtifactStore store) throws IOException;
}
