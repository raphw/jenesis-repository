package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.Publication;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.PomGenerator;
import build.jenesis.repository.format.java.bridge.MavenView;

/**
 * The Maven format's contribution to cross-publishing: when the Jenesis format publishes a module, it hands the module
 * here to also give it a {@code /maven/} view - the jar linked under its derived coordinate over the same
 * content-addressed blob, and a POM computed for that coordinate through {@link PomGenerator}. Discovered by the
 * Jenesis format through {@link ServiceLoader} over the {@link MavenView} contract the shared Java-layout module
 * exports to just these two modules.
 */
public final class MavenViewPublisher implements MavenView {

    private final PomGenerator poms = new PomGenerator();

    @Override
    public void publish(String moduleName, String version, byte[] jar, Publication publication) throws IOException {
        String[] coordinate = JavaLayout.coordinate(moduleName);
        String groupId = coordinate[0], artifactId = coordinate[1];
        String base = "/maven/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version;
        publication.link(base + ".jar", publication.storeBlob(jar));
        publication.publish(base + ".pom", poms.pom(groupId, artifactId, version).getBytes(StandardCharsets.UTF_8));
    }
}
