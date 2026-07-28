package build.jenesis.repository.test;

import build.jenesis.repository.server.CapabilityContributor;
import build.jenesis.repository.server.RepositoryApplication;
import module org.junit.jupiter.api;

import module java.base;
import module java.net.http;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WFE.1 - the free-core {@link CapabilityContributor} SPI. The unit half exercises the documented merge/precedence rule
 * directly (base-map-unchanged with zero contributors, extension, base-wins-on-conflict, graceful zero), so the free
 * product's byte-for-byte guarantee is proven without a server boot; the end-to-end half boots the real free server -
 * which discovers {@link TestCapabilityContributor} through this test module's {@code provides} clause via
 * {@link java.util.ServiceLoader}, exactly as a richer distribution would - and proves a contributor's data is merged
 * onto the one free-served {@code /api/capabilities} without a bean override.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CapabilityContributorTest {

    @TempDir
    private static Path store;

    private RepositoryApplication.Running server;
    private HttpClient client;
    private String base;

    // --- unit: the merge / precedence rule -------------------------------------------------------------------------

    /** The free base map the controller builds, in its served order. */
    private static Map<String, Object> baseMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("readOnly", false);
        map.put("auth", true);
        map.put("anonymousRights", "");
        return map;
    }

    @Test
    public void zero_contributors_leave_the_base_map_byte_for_byte_unchanged() {
        Map<String, Object> merged = CapabilityContributor.merge(baseMap(), List.of());
        // Same keys, same order, same values - the free product's guarantee.
        assertThat(merged).containsExactlyEntriesOf(baseMap());
        assertThat(new ArrayList<>(merged.keySet()))
                .as("base key order is preserved with no contributors")
                .containsExactly("readOnly", "auth", "anonymousRights");
    }

    @Test
    public void a_contributor_extends_the_map_appending_its_keys_after_the_base_keys() {
        CapabilityContributor extra = () -> Map.of("formats", List.of("maven", "docker"), "moduleFlag", true);
        Map<String, Object> merged = CapabilityContributor.merge(baseMap(), List.of(extra));
        assertThat(merged).containsAllEntriesOf(baseMap());
        assertThat(merged).containsEntry("formats", List.of("maven", "docker")).containsEntry("moduleFlag", true);
        assertThat(new ArrayList<>(merged.keySet()))
                .as("base keys keep their order first, contributor keys are appended")
                .startsWith("readOnly", "auth", "anonymousRights");
    }

    @Test
    public void a_base_key_always_wins_a_conflict_with_a_contributor() {
        // A contributor tries to flip readOnly and add a new key; the base value must be kept, the new key merged.
        CapabilityContributor conflicting = () -> Map.of("readOnly", true, "enterprise", "on");
        Map<String, Object> merged = CapabilityContributor.merge(baseMap(), List.of(conflicting));
        assertThat(merged.get("readOnly")).as("base wins on conflict - a contributor cannot shadow the free flag")
                .isEqualTo(false);
        assertThat(merged).containsEntry("enterprise", "on");
    }

    @Test
    public void among_contributors_the_first_discovered_wins() {
        CapabilityContributor first = () -> Map.of("shared", "first");
        CapabilityContributor second = () -> Map.of("shared", "second");
        Map<String, Object> merged = CapabilityContributor.merge(baseMap(), List.of(first, second));
        assertThat(merged.get("shared")).isEqualTo("first");
    }

    @Test
    public void a_null_or_empty_contribution_is_handled_gracefully() {
        CapabilityContributor nothing = () -> null;
        CapabilityContributor empty = Map::of;
        Map<String, Object> merged = CapabilityContributor.merge(baseMap(), List.of(nothing, empty));
        assertThat(merged).containsExactlyEntriesOf(baseMap());
    }

    // --- end-to-end: real ServiceLoader discovery through the live endpoint -----------------------------------------

    @BeforeAll
    public void boot() {
        System.setProperty("JENESIS_STORE_ROOT", store.toString());
        System.setProperty("jenesis.repository.auth", "false");
        server = RepositoryApplication.start(0);
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port();
    }

    @AfterAll
    public void shutdown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("JENESIS_STORE_ROOT");
        System.clearProperty("jenesis.repository.auth");
    }

    @Test
    public void the_discovered_contributor_is_merged_onto_the_live_capabilities_endpoint() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/capabilities")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        // The base keys are still served...
        assertThat(body).contains("\"readOnly\":").contains("\"auth\":").contains("\"anonymousRights\":");
        // ...and the ServiceLoader-discovered contributor's data is merged in, with no bean override.
        assertThat(body).as("the test contributor's data is merged into the one free /api/capabilities")
                .contains("\"" + TestCapabilityContributor.MARKER_KEY + "\":")
                .contains("\"testModuleFlag\":true");
    }
}
