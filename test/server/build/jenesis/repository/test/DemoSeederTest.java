package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.server.DemoSeeder;
import build.jenesis.repository.server.DemoSeeding;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage of demo mode: the {@link DemoSeeder} collects every installed format's suggestions and pulls each
 * through its format's own upstream (a stub {@link ProxyFormat.Fetcher} over canned bytes stands in for the network),
 * the empty-repo guard refuses a non-empty artifact space and fetches nothing, and the {@link DemoSeeding} trigger is
 * a no-op when the flag is off. The demo-gate-quarantine leg (an old, benign-but-vulnerable coordinate hitting a
 * version-floor) is enterprise, exercised in the enterprise gate suite.
 */
class DemoSeederTest {

    private static final String LOG4J_JAR =
            "/maven/org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar";
    private static final String LOG4J_POM =
            "/maven/org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.pom";
    private static final String COMMONS_COLLECTIONS_JAR =
            "/maven/commons-collections/commons-collections/3.2.1/commons-collections-3.2.1.jar";
    private static final String CENTRAL = "https://repo1.maven.org/maven2/";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null)
                .scope("default").scope("default");
    }

    @Test
    void collectsSuggestionsPerFormat() {
        DemoSeeder seeder = new DemoSeeder(List.of(new MavenFormat(), new FakeFormat()), ProxyFormat.Fetcher.NONE);
        assertThat(seeder.suggestions())
                .containsExactly(LOG4J_POM, LOG4J_JAR, COMMONS_COLLECTIONS_JAR, "/fake/demo/example-1.0.bin");
    }

    @Test
    void pullsSuggestionsThroughTheDispatcher() throws IOException {
        StubFetcher fetcher = new StubFetcher(centralArtifacts());

        DemoSeeder.Result result = new DemoSeeder(List.of(new MavenFormat()), fetcher).seed(store);

        assertThat(result.ran()).isTrue();
        assertThat(result.seeded()).isEqualTo(3);
        assertThat(result.unavailable()).isZero();
        // The artifacts streamed through the normal pull-through pipeline into the store (publish pointers linked).
        assertThat(store.readVersioned("publish" + LOG4J_JAR)).isPresent();
        assertThat(store.readVersioned("publish" + LOG4J_POM)).isPresent();
        assertThat(store.readVersioned("publish" + COMMONS_COLLECTIONS_JAR)).isPresent();
        assertThat(DemoSeeder.empty(store)).isFalse();
        assertThat(fetcher.fetches()).isPositive();
    }

    @Test
    void refusesANonEmptyArtifactSpace() throws IOException {
        // A single published pointer makes the space non-empty (a seeded or in-use repository).
        store.writeVersioned("publish/maven/com/acme/app/1.0/app-1.0.jar",
                "0".repeat(64).getBytes(StandardCharsets.UTF_8), null);
        StubFetcher fetcher = new StubFetcher(Map.of());

        DemoSeeder.Result result = new DemoSeeder(List.of(new MavenFormat()), fetcher).seed(store);

        assertThat(result.ran()).as("a non-empty store is never seeded").isFalse();
        assertThat(result.seeded()).isZero();
        assertThat(fetcher.fetches()).as("nothing is fetched when the guard refuses").isZero();
    }

    @Test
    void triggerIsANoOpWhenTheFlagIsOff() {
        StubFetcher fetcher = new StubFetcher(Map.of());
        AtomicInteger beforeSeed = new AtomicInteger();
        DemoSeeding seeding = new DemoSeeding(false, new DemoSeeder(List.of(new MavenFormat()), fetcher),
                store, beforeSeed::incrementAndGet);

        seeding.start();

        assertThat(DemoSeeder.empty(store)).as("flag off seeds nothing").isTrue();
        assertThat(beforeSeed.get()).isZero();
        assertThat(fetcher.fetches()).isZero();
    }

    @Test
    void triggerSeedsInTheBackgroundWhenOn() throws IOException, InterruptedException {
        StubFetcher fetcher = new StubFetcher(centralArtifacts());
        AtomicInteger beforeSeed = new AtomicInteger();
        DemoSeeding seeding = new DemoSeeding(true, new DemoSeeder(List.of(new MavenFormat()), fetcher),
                store, beforeSeed::incrementAndGet);

        // The seed runs on a background virtual thread so boot is never blocked; join it so every write has landed
        // before the assertions and the @TempDir teardown - otherwise the thread races the temp-dir deletion.
        Thread seeder = seeding.start();
        seeder.join(10_000);
        assertThat(seeder.isAlive()).as("the background seed finished within the deadline").isFalse();

        assertThat(DemoSeeder.empty(store)).as("demo seeding populated the space").isFalse();
        assertThat(beforeSeed.get()).as("the pre-seed hook ran exactly once, before seeding").isEqualTo(1);
        assertThat(store.readVersioned("publish" + LOG4J_JAR)).isPresent();
    }

    /** Canned upstream responses for every Maven demo suggestion, so a stub fetcher serves them all without a
     *  network (each mapped by its Maven Central URL; a {@code .sha1} sibling is deliberately unmapped so the proxy
     *  skips checksum verification). */
    private static Map<String, ProxyFormat.Fetched> centralArtifacts() {
        return Map.of(
                CENTRAL + "org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar",
                new ProxyFormat.Fetched(200, "fake-jar-bytes".getBytes(StandardCharsets.UTF_8), Map.of()),
                CENTRAL + "org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.pom",
                new ProxyFormat.Fetched(200, "<project/>".getBytes(StandardCharsets.UTF_8), Map.of()),
                CENTRAL + "commons-collections/commons-collections/3.2.1/commons-collections-3.2.1.jar",
                new ProxyFormat.Fetched(200, "fake-jar-bytes".getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    /** A hosted-only format that carries a demo suggestion but no upstream, to prove suggestions are gathered across
     *  every format (its own pull-through simply seeds nothing without a {@code defaultUpstream}). */
    private static final class FakeFormat implements RepositoryFormat {

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/fake/");
        }

        @Override
        public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
            exchange.respond(404);
        }

        @Override
        public List<String> demoArtifacts() {
            return List.of("/fake/demo/example-1.0.bin");
        }
    }

    /** A fixed in-memory fetcher answering each URL from a canned map (an unmapped URL - including any {@code .sha1}
     *  sibling - is a transport miss, so the Maven proxy skips checksum verification) and counting its calls. */
    private static final class StubFetcher implements ProxyFormat.Fetcher {

        private final Map<String, ProxyFormat.Fetched> responses;
        private final AtomicInteger fetches = new AtomicInteger();

        private StubFetcher(Map<String, ProxyFormat.Fetched> responses) {
            this.responses = responses;
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
            fetches.incrementAndGet();
            return Optional.ofNullable(responses.get(url.toString()));
        }

        private int fetches() {
            return fetches.get();
        }
    }
}
