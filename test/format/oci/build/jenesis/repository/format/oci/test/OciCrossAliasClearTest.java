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
        return pushManifest(name, reference, manifest, store);
    }

    private int pushManifest(String name, String reference, byte[] manifest, ArtifactStore against) throws IOException {
        FakeExchange put = new FakeExchange("PUT", "/v2/" + name + "/manifests/" + reference, manifest,
                Map.of(), Map.of("Content-Type", TYPE));
        format.handle(put, against);
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

    /** Audit-27 A5-F2: the ACCEPT-clear cross-alias guard is a read-THEN-clear, a TOCTOU twin of the reconcile-vs-
     *  enforce race #207. A concurrent enforce sweep that links a byte-identical sibling's {@code /quarantine} pointer
     *  in the window between the guard read and the clear would leave the still-live hold's content-addressed marker
     *  gone - and the OCI serve gate keys withheld on the MARKER - so the KEV-held sibling would disclose. This pins
     *  the post-clear re-verify: a delegating store injects sibling B's pointer at the exact instant the clear deletes
     *  {@code withheld/<hex>} (the enforce landing inside the window), and the re-verify must find it and RE-MARK, so
     *  the marker ends up PRESENT and the sibling stays withheld. */
    @Test
    void an_enforce_that_links_a_sibling_in_the_clear_window_is_caught_by_the_post_clear_reverify()
            throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\",\"config\":{}}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        // A and B serve the identical bytes. A retroactive sweep has written the shared content-addressed marker, but
        // NO /quarantine pointer stands for the hash yet - so at guard-read time the cross-alias probe sees nothing and
        // the ACCEPT re-push of A would clear (the correct read-time verdict: nothing holds it at that instant).
        assertThat(pushManifest("liba/app", "1.0", manifest)).isEqualTo(201);
        assertThat(pushManifest("libb/app", "1.0", manifest)).isEqualTo(201);
        Withheld.mark(store, hex);
        assertThat(getStatus("/v2/liba/app/manifests/1.0")).as("A is 404 while the marker stands").isEqualTo(404);

        // The window: a delegating store that, at the instant Withheld.clear deletes withheld/<hex>, first links the
        // byte-identical sibling B's /quarantine pointer (body == hex) - a concurrent enforce sweep landing its hold
        // AFTER the guard read but BEFORE (interleaved with) the clear. The post-clear re-verify must then observe B's
        // fresh pointer and re-mark, so the sibling's live hold is not stranded marker-less.
        ArtifactStore racy = new InjectingStore(store, "withheld/" + hex,
                () -> holdAlias("libb/app", "1.0", hex));

        assertThat(pushManifest("liba/app", "1.0", manifest, racy)).as("the re-push is accepted (201)").isEqualTo(201);
        assertThat(Withheld.is(store, hex))
                .as("the post-clear re-verify re-marked, so the marker the enforce raced in survives").isTrue();
        assertThat(getStatus("/v2/libb/app/manifests/1.0")).as("the sibling B the enforce held stays 404")
                .isEqualTo(404);
        assertThat(getStatus("/v2/liba/app/manifests/1.0")).as("A also stays 404 on the re-marked shared marker")
                .isEqualTo(404);
    }

    /** Audit-28 A5-F1: the post-clear re-verify must re-run the FULL guard face, not only the cross-alias probe. The
     *  guard passes on two faces - the same-path chain probe (disclosable(path), THIS path carries no /quarantine
     *  pointer) AND the cross-alias scan (no OTHER alias holds the hash). The earlier re-verify re-ran only the
     *  cross-alias scan with {@code path} EXCLUDED, so an enforce that lands its hold on THIS SAME path in the clear
     *  window - linking {@code /quarantine<path>} and marking - was invisible to it: the marker stayed wrongly cleared
     *  and the freshly KEV-held manifest disclosed by digest for up to one enforce interval, with no reconcile owner on
     *  the free side to re-mark it. This pins the same-path variant: a delegating store links THIS path's own pointer
     *  at the instant the clear deletes {@code withheld/<hex>}, and the re-verify must observe {@code disclosable(path)}
     *  now false and RE-MARK. */
    @Test
    void an_enforce_that_holds_this_same_path_in_the_clear_window_is_caught_by_the_post_clear_reverify()
            throws IOException {
        byte[] manifest = ("{\"mediaType\":\"" + TYPE + "\",\"config\":{}}").getBytes(StandardCharsets.UTF_8);
        String hex = sha256(manifest);

        // One image A. A retroactive sweep has written the shared content-addressed marker, but NO /quarantine pointer
        // stands for the hash yet - so at guard-read time BOTH faces pass (disclosable(A) is true, no cross-alias
        // sibling holds the hash) and the ACCEPT re-push of A would clear (the correct read-time verdict: nothing holds
        // it at that instant).
        assertThat(pushManifest("liba/app", "1.0", manifest)).isEqualTo(201);
        Withheld.mark(store, hex);
        assertThat(getStatus("/v2/liba/app/manifests/1.0")).as("A is 404 while the marker stands").isEqualTo(404);

        // The window: a concurrent enforce sweep lands its hold on A's OWN path (links /quarantine<A> pointer, body ==
        // hex) at the instant Withheld.clear deletes withheld/<hex> - AFTER the guard read but interleaved with the
        // clear. The post-clear re-verify must then observe disclosable(A) now false (its own pointer stands) and
        // re-mark, so A's freshly-landed hold is not stranded marker-less.
        ArtifactStore racy = new InjectingStore(store, "withheld/" + hex,
                () -> holdAlias("liba/app", "1.0", hex));

        assertThat(pushManifest("liba/app", "1.0", manifest, racy)).as("the re-push is accepted (201)").isEqualTo(201);
        assertThat(Withheld.is(store, hex))
                .as("the post-clear re-verify re-marked on the same-path face, so the raced-in hold survives").isTrue();
        assertThat(getStatus("/v2/liba/app/manifests/1.0")).as("A stays 404 on the re-marked marker").isEqualTo(404);
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

    /** A store action that can fail with {@link IOException} - the sibling-linking injection the window test runs. */
    @FunctionalInterface
    private interface StoreAction {
        void run() throws IOException;
    }

    /** A delegating {@link ArtifactStore} that reproduces the ACCEPT-clear TOCTOU window deterministically: the first
     *  time the clear deletes the withhold marker ({@code delete("withheld/<hex>")}), it FIRST runs {@code inject}
     *  (which links a byte-identical sibling's {@code /quarantine} pointer) and then performs the delete - modelling a
     *  concurrent enforce sweep that lands its hold between the guard read and the clear. Every other operation is a
     *  straight pass-through to the real store, so the post-clear re-verify reads fresh truth through this same view. */
    private static final class InjectingStore implements ArtifactStore {

        private final ArtifactStore delegate;
        private final String markerKey;
        private final StoreAction inject;
        private boolean injected;

        private InjectingStore(ArtifactStore delegate, String markerKey, StoreAction inject) {
            this.delegate = delegate;
            this.markerKey = markerKey;
            this.inject = inject;
        }

        @Override
        public void delete(String key) throws IOException {
            if (key.equals(markerKey) && !injected) {
                injected = true;   // the enforce links the sibling's /quarantine pointer inside the clear window, once
                inject.run();
            }
            delegate.delete(key);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
