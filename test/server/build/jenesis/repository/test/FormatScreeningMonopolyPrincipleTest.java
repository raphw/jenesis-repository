package build.jenesis.repository.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Free-core structural guard (EPIC 26): screening is the ingress edges' monopoly, so no repository format or importer
 * may run the publish-screen chain itself. Screening was lifted out of the formats onto the ingress edges - the deploy
 * edge ({@code ScreenedDispatch}), the batch explode, the import walk and OCI's manifest choke point - and the formats
 * were demoted to pure layout writers. This is a source-scanning guard in the shape of the enterprise structural guards
 * (a {@code *PrincipleTest} that reads the sources rather than booting anything): it walks every concrete
 * format/importer source under {@code source/format/} and asserts none reaches for a screening seam - it neither
 * references the screen SPI type {@code PublishInterceptor}, nor invokes {@link build.jenesis.repository.store.Publication#screen},
 * nor the removed combined {@code Publication.publish}. A NEW screen-in-a-format therefore fails the build.
 *
 * <p>{@code OciManifests} is the single sanctioned exception, allowlisted explicitly below with its justification: OCI's
 * multi-request {@code /v2/} push carries no single body for the {@code ScreenedDispatch} edge, so OCI opts out of that
 * edge and screens its manifest at its own documented choke point (T26.7), running the one in-format
 * {@link build.jenesis.repository.store.Publication#screen}. Keeping the exception a named, justified allowlist entry
 * (rather than a silent scope hole) means any other format that starts screening is caught, and this OCI carve-out
 * stays visible for review.
 *
 * <p>The {@code source/format/spi} contract module is out of scope: it is the format <em>SPI</em>, not a format or
 * importer, and its interface javadoc necessarily names the screen SPI when documenting where screening happens - it
 * lays nothing out and screens nothing.
 */
class FormatScreeningMonopolyPrincipleTest {

    /** The one sanctioned in-format screen: OCI's manifest choke point (T26.7), which has no single-body ingress edge
     *  to ride because a {@code /v2/} push is multi-request, so it runs {@code Publication.screen} over the manifest
     *  itself. Named here so the carve-out is explicit and any other screening format is caught. */
    private static final String ALLOWLISTED_OCI_CHOKE_POINT = "OciManifests.java";

    /** The format SPI contract module - interfaces the concrete formats implement, not a format itself; its javadoc
     *  names the screen SPI when documenting the edge relationship, so it is not scanned. */
    private static final String CONTRACT_MODULE = "spi";

    /** The screening seams a demoted format/importer must never reach for. {@code PublishInterceptor} is the screen SPI
     *  type; {@code .screen(} is the screen invocation; {@code Publication.publish} / {@code Publication#publish} is the
     *  removed combined screen-and-link. Layout-only use of {@code Publication} ({@code storeBlob}, {@code link},
     *  {@code located}, {@code unpublish}) and the {@code ModuleView}/{@code ModuleViewPublisher} cross-publish
     *  ({@code view.publish(...)}) are format concerns and stay allowed. */
    private static final List<String> FORBIDDEN = List.of(
            "PublishInterceptor", ".screen(", "Publication.publish", "Publication#publish");

    @Test
    void no_format_or_importer_screens_outside_the_ingress_edges() throws IOException {
        Path formats = repositoryRoot().resolve("source").resolve("format");
        assertThat(Files.isDirectory(formats))
                .as("the format sources must be present for the guard to scan them: " + formats).isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(formats)) {
            List<Path> files = sources.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path source : files) {
                String name = source.getFileName().toString();
                if (name.equals("module-info.java") || name.equals(ALLOWLISTED_OCI_CHOKE_POINT)) {
                    continue; // OciManifests is the single allowlisted OCI structural choke point
                }
                if (formats.relativize(source).getName(0).toString().equals(CONTRACT_MODULE)) {
                    continue; // the format SPI contract module is not a format/importer
                }
                String body = Files.readString(source);
                for (String forbidden : FORBIDDEN) {
                    if (body.contains(forbidden)) {
                        offenders.add(name + " references '" + forbidden + "'");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("screening is the ingress edges' monopoly - a format/importer must lay out only, never screen; "
                        + OciManifests_note())
                .isEmpty();
    }

    private static String OciManifests_note() {
        return ALLOWLISTED_OCI_CHOKE_POINT + " is the sole allowlisted exception (the documented OCI manifest choke point)";
    }

    /** The repository root: walk up from the working directory (the reactor runs each test JVM from the repo root)
     *  until the {@code source/format} tree is found, so the guard locates the sources whether the suite runs from the
     *  root or a nested module directory. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("source").resolve("format"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate a repository root containing source/format from "
                + Path.of("").toAbsolutePath());
    }
}
