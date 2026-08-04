package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Withheld;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OCI choke point's migration to the {@code withheld/<hash>} marker idiom and the withhold-change feed (Audit-23,
 * phase P3). {@code OciManifests.ingest} now marks a held manifest through {@link Withheld#mark} (joining the feed and
 * the one marker idiom) and, on an accepted push, clears through {@link Withheld#clear} - but only when no retroactive
 * hold's review pointer still stands on the manifest path (§6 Q-D). The standing hold is supplied here by the discovered
 * {@link OciHoldInterceptor}, which withholds any {@code retro-held} path exactly as an enterprise {@code ComplianceScreen}
 * would after a retroactive KEV/license/reachability sweep.
 */
class OciWithholdFeedTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final build.jenesis.repository.format.oci.OciFormat format = new build.jenesis.repository.format.oci.OciFormat();

    private static final String TYPE = "application/vnd.oci.image.manifest.v1+json";

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private int pushManifest(String name, String reference, byte[] manifest) throws IOException {
        FakeExchange put = new FakeExchange("PUT", "/v2/" + name + "/manifests/" + reference, manifest,
                Map.of(), Map.of("Content-Type", TYPE));
        format.handle(put, store);
        return put.status();
    }

    private int getStatus(String path) throws IOException {
        FakeExchange get = new FakeExchange("GET", path);
        format.handle(get, store);
        return get.status();
    }

    @Test
    void an_accepted_repush_no_longer_clears_a_standing_retroactive_hold() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);
        // A retroactive KEV/license sweep marked these bytes withheld after they were first served.
        Withheld.mark(store, hex);

        // The operator re-pushes the identical, now-accepted manifest. Its path (retro-held/app) still carries the
        // standing hold's review pointer (the discovered OciHoldInterceptor withholds it), so the ACCEPT-clear is
        // narrowed away - the marker must survive rather than the screen-time ACCEPT self-releasing a standing hold.
        assertThat(pushManifest("retro-held/app", "1.0", manifest)).as("the re-push is accepted (201)").isEqualTo(201);
        assertThat(Withheld.is(store, hex))
                .as("a screen-time ACCEPT does not tear down a standing retroactive hold").isTrue();
        assertThat(getStatus("/v2/retro-held/app/manifests/sha256:" + hex))
                .as("the still-held manifest 404s by digest").isEqualTo(404);
        assertThat(getStatus("/v2/retro-held/app/manifests/1.0"))
                .as("the still-held manifest 404s by tag").isEqualTo(404);
    }

    @Test
    void an_accepted_repush_without_a_standing_hold_still_clears_the_marker() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);
        Withheld.mark(store, hex);

        // A plain path carries no standing chain hold, so the accepted re-push clears the (now stale) marker and serves.
        assertThat(pushManifest("plain/app", "1.0", manifest)).as("the re-push is accepted (201)").isEqualTo(201);
        assertThat(Withheld.is(store, hex)).as("with no standing hold the stale marker is cleared").isFalse();
        assertThat(getStatus("/v2/plain/app/manifests/sha256:" + hex))
                .as("the re-accepted manifest serves again").isEqualTo(200);
    }

    @Test
    void a_quarantined_manifest_marks_through_the_feed_convention_and_404s() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\"}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        assertThat(pushManifest("gate-quarantine/app", "1.0", manifest))
                .as("a quarantined manifest push is 202").isEqualTo(202);
        assertThat(Withheld.is(store, hex)).as("the hold is marked through the Withheld convention").isTrue();
        assertThat(store.readVersioned("withheld/" + hex))
                .hasValueSatisfying(v -> assertThat(v.content())
                        .as("Withheld.mark writes an empty body - presence is the signal").isEmpty());
        assertThat(getStatus("/v2/gate-quarantine/app/manifests/sha256:" + hex))
                .as("a held manifest is unpullable by digest").isEqualTo(404);
    }
}
