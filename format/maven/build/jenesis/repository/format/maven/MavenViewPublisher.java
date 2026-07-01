package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.PomGenerator;
import build.jenesis.repository.format.java.bridge.MavenView;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Maven format's contribution to cross-publishing: when the Jenesis format publishes a module, it hands the module
 * here to also give it a {@code /maven/} view - the jar linked under its derived coordinate over the same
 * content-addressed blob, and a POM computed for it through {@link PomGenerator}. The POM's dependencies come from the
 * jar's module descriptor; a required module whose version the descriptor did not record is resolved to the
 * coordinate's latest version known to this repository ({@link MavenMetadata#latest}), which covers everything already
 * published or proxied here, and falls back to the {@code LATEST} keyword otherwise. Discovered by the Jenesis format
 * through {@link ServiceLoader} over the {@link MavenView} contract the shared Java-layout module exports to just
 * these two modules.
 */
public final class MavenViewPublisher implements MavenView {

    private final PomGenerator poms = new PomGenerator();

    @Override
    public void publish(String moduleName, String version, byte[] jar, ArtifactStore store) throws IOException {
        String[] coordinate = JavaLayout.coordinate(moduleName);
        String groupId = coordinate[0], artifactId = coordinate[1];
        String base = "/maven/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version;
        Publication publication = new Publication(store);
        publication.link(base + ".jar", publication.storeBlob(jar));
        MavenMetadata metadata = new MavenMetadata(store);
        String pom = poms.pom(groupId, artifactId, version, jar,
                (group, artifact) -> metadata.latest(group, artifact).orElse("LATEST"));
        publication.publish(base + ".pom", pom.getBytes(StandardCharsets.UTF_8));
    }
}
