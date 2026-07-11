package build.jenesis.repository.store.gcs.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A live smoke against real Google Cloud Storage, opted in through the backend's own settings in the
 * environment: {@code JENESIS_GCS_BUCKET} plus the {@code JENESIS_GCS_ACCESS_KEY_ID} /
 * {@code JENESIS_GCS_SECRET_ACCESS_KEY} HMAC pair (Cloud Storage &gt; Settings &gt; Interoperability).
 * An entitlement is not a tool, so the gate is a plain assumption that skips even on a strict
 * {@code jenesis.test.required} run - CI has no GCS project. What only real GCS can prove: the SigV4
 * HMAC handshake against {@code storage.googleapis.com}, that uploads survive the SDK's checksum
 * configuration, and that the {@code x-goog-if-generation-match} preconditions and the
 * {@code x-goog-generation} token behave on the wire as the {@link GcsConditionalWriteTest} stub
 * asserts. Every object is written under a unique key and removed again, so the bucket stays clean.
 */
@Tag("gcs")
public class GcsLiveTest {

    @Test
    public void a_live_gcs_bucket_streams_and_compare_and_sets() throws IOException {
        assumeTrue(present("JENESIS_GCS_BUCKET")
                        && present("JENESIS_GCS_ACCESS_KEY_ID")
                        && present("JENESIS_GCS_SECRET_ACCESS_KEY"),
                "A live GCS smoke needs JENESIS_GCS_BUCKET and the JENESIS_GCS_ACCESS_KEY_ID/"
                        + "JENESIS_GCS_SECRET_ACCESS_KEY HMAC pair in the environment");
        ArtifactStore store = ArtifactStoreProvider.resolve("gcs", System::getenv).scope("jenesis-gcs-smoke");
        String key = "smoke/" + UUID.randomUUID();
        String casKey = key + ".versioned";
        try {
            byte[] body = {4, 8, 15, 16, 23, (byte) 42};
            assertThat(store.exists(key)).isFalse();
            store.write(key, new ByteArrayInputStream(body));
            assertThat(store.exists(key)).isTrue();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            store.read(key, out);
            assertThat(out.toByteArray()).isEqualTo(body);

            assertThat(store.readVersioned(casKey)).isEmpty();
            assertThat(store.writeVersioned(casKey, "one".getBytes(StandardCharsets.UTF_8), null)).isTrue();
            assertThat(store.writeVersioned(casKey, "two".getBytes(StandardCharsets.UTF_8), null))
                    .as("a second create-if-absent is rejected by GCS's precondition").isFalse();
            Object token = store.readVersioned(casKey).orElseThrow().token();
            assertThat(store.writeVersioned(casKey, "two".getBytes(StandardCharsets.UTF_8), token)).isTrue();
            assertThat(store.writeVersioned(casKey, "three".getBytes(StandardCharsets.UTF_8), token))
                    .as("a stale generation is rejected by GCS's precondition").isFalse();
            assertThat(new String(store.readVersioned(casKey).orElseThrow().content(), StandardCharsets.UTF_8))
                    .isEqualTo("two");
        } finally {
            store.delete(key);
            store.delete(casKey);
        }
    }

    private static boolean present(String variable) {
        String value = System.getenv(variable);
        return value != null && !value.isBlank();
    }
}
