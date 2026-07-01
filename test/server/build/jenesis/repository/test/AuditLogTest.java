package build.jenesis.repository.test;

import build.jenesis.repository.server.AuditLog;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit log records security-relevant events to the store and queries them back per tenant, filtered by action
 * and time, newest first; a disabled log records nothing.
 */
class AuditLogTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    @Test
    void it_records_and_queries_events_filtered_by_action_and_tenant() throws IOException {
        AuditLog audit = new AuditLog(store, true, null);
        audit.record("acme", "hash1", "mint", "cred-x");
        audit.record("acme", "hash1", "revoke", "cred-x");
        audit.record("globex", "hashG", "mint", "cred-y");

        assertThat(audit.query("acme", null, null, null)).hasSize(2);
        assertThat(audit.query("acme", null, null, "mint")).singleElement()
                .satisfies(event -> assertThat(event.target()).isEqualTo("cred-x"));
        assertThat(audit.query("globex", null, null, null)).singleElement()
                .satisfies(event -> assertThat(event.actor()).isEqualTo("hashG"));
    }

    @Test
    void it_orders_newest_first_and_honours_a_time_bound() throws IOException {
        AuditLog audit = new AuditLog(store, true, null);
        audit.record("acme", "a", "mint", "one");
        audit.record("acme", "a", "mint", "two");
        assertThat(audit.query("acme", null, null, null)).first()
                .satisfies(event -> assertThat(event.target()).isEqualTo("two"));
        assertThat(audit.query("acme", Instant.now().plusSeconds(60), null, null))
                .as("a future lower bound excludes past events").isEmpty();
    }

    @Test
    void a_disabled_log_records_nothing() throws IOException {
        AuditLog audit = new AuditLog(store, false, null);
        audit.record("acme", "h", "mint", "x");
        assertThat(audit.query("acme", null, null, null)).isEmpty();
    }

    @Test
    void it_prunes_day_folders_older_than_the_retention_window() throws IOException {
        AuditLog audit = new AuditLog(store, true, java.time.Duration.ofDays(30));
        store.write("audit/acme/2020-01-01/100-1", new java.io.ByteArrayInputStream(
                "at=2020-01-01T00:00:00Z\nactor=old\naction=stale\ntarget=x\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(audit.query("acme", null, null, null)).as("the stale event is present before pruning").hasSize(1);

        audit.record("acme", "a", "mint", "fresh");
        assertThat(audit.query("acme", null, null, null)).extracting(AuditLog.Event::action)
                .as("a recording prunes the stale day, leaving only fresh events").containsExactly("mint");
    }
}
