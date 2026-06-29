package build.jenesis.repository;

import module java.base;

/**
 * Emits the {@code .pom} (and the {@code maven-metadata.xml} entry) for a module uploaded under
 * {@code /module/...}, so Maven clients can consume it. A skeleton: the production generator reuses the
 * jenesis {@code MavenPomEmitter} and the module-descriptor parser to read the module-info, derive the
 * coordinate, and carry the resolved dependencies as {@code <dependencies>}.
 */
public final class PomGenerator {

    /** A minimal POM for a coordinate. The production generator additionally carries dependencies and metadata. */
    public String pom(String groupId, String artifactId, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version);
    }
}
