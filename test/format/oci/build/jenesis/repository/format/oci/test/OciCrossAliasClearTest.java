package build.jenesis.repository.format.oci.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Withheld;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OCI ACCEPT-clear cross-alias guard (Audit-26 F3): the manifest choke point's {@code Withheld.clear} on an accepted
 * (re-)push must prove no OTHER alias still holds the content-addressed marker, not only that THIS path carries no
 * standing hold. The withhold marker is content-addressed (one {@code withheld/<hex>} withholds the bytes wherever
 * served) and the OCI serve gate keys on the marker, not the per-path {@code /quarantine} pointer - so clearing it while
 * a byte-identical sibling image is still held would un-withhold that sibling. The same-path chain probe never sees the
 * sibling's pointer; {@code Publication.quarantineAliasExists} (the free-store cross-alias scan) does.
 */
class OciCrossAliasClearTest {

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

    /** Stand a retroactive hold's review pointer whose body is the manifest hex on an image's manifest path, the
     *  OciHoldRecorder convention {@code quarantineAliasExists} scans. */
    private void holdAlias(String name, String reference, String hex) throws IOException {
        store.write("publish/quarantine/v2/" + name + "/manifests/" + reference,
                new ByteArrayInputStream(hex.getBytes(StandardCharsets.UTF_8)));
    }

    private void releaseAlias(String name, String reference) throws IOException {
        store.delete("publish/quarantine/v2/" + name + "/manifests/" + reference);
    }

    @Test
    void an_accepted_repush_under_one_alias_keeps_the_marker_while_a_byte_identical_sibling_is_still_held()
            throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\",\"config\":{}}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        // Two images A and B serve the identical manifest bytes (a mirror / one image, two names). Both push and serve.
        assertThat(pushManifest("liba/app", "1.0", manifest)).isEqualTo(201);
        assertThat(pushManifest("libb/app", "1.0", manifest)).isEqualTo(201);
        assertThat(getStatus("/v2/liba/app/manifests/1.0")).as("A serves before the hold").isEqualTo(200);
        assertThat(getStatus("/v2/libb/app/manifests/1.0")).as("B serves before the hold").isEqualTo(200);

        // A retroactive KEV/license sweep holds BOTH: one shared content-addressed marker + each image's own review
        // pointer (body == the manifest hex). Both now 404 by tag and digest.
        Withheld.mark(store, hex);
        holdAlias("liba/app", "1.0", hex);
        holdAlias("libb/app", "1.0", hex);
        assertThat(getStatus("/v2/liba/app/manifests/1.0")).as("A is 404 while held").isEqualTo(404);
        assertThat(getStatus("/v2/libb/app/manifests/1.0")).as("B is 404 while held").isEqualTo(404);

        // A is released: its review pointer is dropped, but the shared marker stays because B still holds the hash.
        releaseAlias("liba/app", "1.0");

        // The identical, now-accepted manifest is re-pushed under the released alias A. The same-path chain probe passes
        // (A carries no pointer, no interceptor), but the cross-alias scan finds B's live pointer body == hex, so the
        // marker must NOT be cleared - B's standing hold outranks A's screen-time ACCEPT.
        assertThat(pushManifest("liba/app", "1.0", manifest)).as("the re-push is accepted (201)").isEqualTo(201);
        assertThat(Withheld.is(store, hex))
                .as("the shared marker survives while the byte-identical sibling B is still held").isTrue();
        assertThat(getStatus("/v2/libb/app/manifests/1.0")).as("the still-held sibling B stays 404").isEqualTo(404);
        assertThat(getStatus("/v2/liba/app/manifests/1.0")).as("A also stays 404 on the shared marker").isEqualTo(404);
    }

    @Test
    void once_the_last_holding_alias_is_released_an_accepted_repush_clears_the_marker() throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\",\"config\":{}}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        assertThat(pushManifest("liba/app", "1.0", manifest)).isEqualTo(201);
        assertThat(pushManifest("libb/app", "1.0", manifest)).isEqualTo(201);
        Withheld.mark(store, hex);
        holdAlias("liba/app", "1.0", hex);
        holdAlias("libb/app", "1.0", hex);

        // Both aliases are released (no live quarantine pointer holds the hash). The control for the guard above: with no
        // sibling still held, the rule-change re-push contract is preserved - the stale marker clears and A serves.
        releaseAlias("liba/app", "1.0");
        releaseAlias("libb/app", "1.0");
        assertThat(pushManifest("liba/app", "1.0", manifest)).as("the re-push is accepted (201)").isEqualTo(201);
        assertThat(Withheld.is(store, hex)).as("with no alias still holding the hash the marker clears").isFalse();
        assertThat(getStatus("/v2/liba/app/manifests/1.0")).as("A serves again once nothing holds the hash")
                .isEqualTo(200);
    }
}
