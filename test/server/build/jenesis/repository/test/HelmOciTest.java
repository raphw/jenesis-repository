package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryApplication;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.test.Requirement.requireOrSkip;

/**
 * Verifies that Helm works against the repository with no Helm-specific code: a Helm 3 chart is an OCI artifact, so
 * {@code helm push} and {@code helm pull} go through the {@link build.jenesis.repository.format.oci.OciFormat} {@code /v2/}
 * registry. The test boots a {@link RepositoryApplication} with the OCI plugin, packages a chart, pushes it over plain
 * HTTP, then pulls it back into a clean directory. The {@code helm} client runs from a pinned Docker image
 * ({@link ToolContainer}, host-networked so it reaches the host's ephemeral registry port) rather than a {@code helm}
 * the developer had to install; the suite skips itself when no Docker is available.
 */
@Tag("helm")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HelmOciTest {

    private static final String IMAGE = "alpine/helm:3.16.3@sha256:ed9dfc49d43d034df3f9880eb777caf0183e5156508672478b80412c63f3db4f";

    @TempDir
    static Path root;

    private RepositoryApplication.Running running;
    private ToolContainer tool;
    private String lastOutput = "";

    @BeforeAll
    public void start() {
        requireOrSkip(ToolContainer.dockerAvailable(), "Docker is required for the Helm-over-OCI verification");
        System.setProperty("JENESIS_STORE_ROOT", root.resolve("store").toString());
        // Auth now defaults on; this test exercises the feature, not authorization, so pin the anonymous
        // (auth=false) opt-out to preserve its intent - the request path stays unauthenticated.
        System.setProperty("jenesis.repository.auth", "false");
        running = RepositoryApplication.start(0);
        tool = ToolContainer.start(IMAGE, root);
    }

    @AfterAll
    public void stop() {
        if (tool != null) {
            tool.close();
        }
        if (running != null) {
            running.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void a_chart_pushes_and_pulls_over_the_oci_registry() throws Exception {
        Path chart = Files.createDirectories(root.resolve("jenesis-chart"));
        Files.createDirectories(chart.resolve("templates"));
        Files.writeString(chart.resolve("Chart.yaml"),
                "apiVersion: v2\nname: jenesis-chart\nversion: 0.1.0\ndescription: a helm-over-oci demo\n");

        assertThat(helm(60, "package", inContainer(chart), "-d", "/work"))
                .as("helm package: " + lastOutput).isZero();
        Path packaged = root.resolve("jenesis-chart-0.1.0.tgz");

        String registry = "oci://localhost:" + running.port() + "/charts";
        assertThat(helm(60, "push", inContainer(packaged), registry, "--plain-http"))
                .as("helm push: " + lastOutput).isZero();

        Path destination = Files.createDirectories(root.resolve("pulled"));
        assertThat(helm(60, "pull", registry + "/jenesis-chart", "--version", "0.1.0", "--plain-http",
                "-d", inContainer(destination)))
                .as("helm pull: " + lastOutput).isZero();
        assertThat(Files.exists(destination.resolve("jenesis-chart-0.1.0.tgz")))
                .as("the chart pulled back from the OCI registry").isTrue();
    }

    private int helm(int timeoutSeconds, String... arguments) throws IOException, InterruptedException {
        // Keep Helm's caches inside the bind-mounted work dir so the container has a writable home for them.
        List<String> command = new ArrayList<>(List.of("env",
                "HELM_CACHE_HOME=/work/helm-cache",
                "HELM_CONFIG_HOME=/work/helm-config",
                "HELM_DATA_HOME=/work/helm-data",
                "helm"));
        Collections.addAll(command, arguments);
        ToolContainer.Result result = tool.exec(Duration.ofSeconds(timeoutSeconds), command.toArray(new String[0]));
        lastOutput = result.output();
        return result.exitCode();
    }

    /** The path a work-directory file has inside the client container, where {@code root} is bind-mounted at /work. */
    private static String inContainer(Path path) {
        return "/work/" + root.relativize(path).toString().replace(File.separatorChar, '/');
    }
}
