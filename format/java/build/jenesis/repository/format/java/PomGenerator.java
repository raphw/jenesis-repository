package build.jenesis.repository.format.java;

import module java.base;

/**
 * Emits the {@code .pom} for a module cross-published under its Maven coordinate, so Maven clients can consume a
 * Jenesis module. A coordinate-only POM today; the richer generator additionally reads the module descriptor and
 * carries its {@code requires}, mapped through the module bridge, as {@code <dependencies>}.
 */
public final class PomGenerator {

    /** A minimal POM for a coordinate. */
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
