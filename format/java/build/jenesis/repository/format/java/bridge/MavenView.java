package build.jenesis.repository.format.java.bridge;

import module java.base;
import build.jenesis.repository.Publication;

/**
 * The Maven-layout side of cross-publishing: provided by the Maven format, used by the Jenesis format. Given a module
 * published under {@code /module/}, it publishes the module's {@code /maven/} view over the same content-addressed
 * blob - the jar under its derived coordinate and a POM computed for it - so a Maven client reaches the artifact a
 * Jenesis client published by module name. Like {@link ModuleView}, this is not part of the public
 * {@code RepositoryFormat} SPI; it is a bridge exposed only between the Maven and Jenesis layout modules.
 */
public interface MavenView {

    void publish(String moduleName, String version, byte[] jar, Publication publication) throws IOException;
}
