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

    /**
     * Retract the {@code /module/} view {@link #publish} linked for a module - the exact counterpart of a publish, so
     * an artifact retracted from its Maven coordinate (a proxied jar that failed its upstream checksum) is unreachable
     * by module name too, not merely by coordinate. The same view module that owns the publish-side path derivation
     * owns its removal, so the Maven format never hardcodes a parallel {@code /module/} path. Best-effort per pointer:
     * a view the module never gained is a no-op.
     */
    void unpublish(String moduleName, String version, ArtifactStore store) throws IOException;
}
