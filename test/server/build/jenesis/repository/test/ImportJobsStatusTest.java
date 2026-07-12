package build.jenesis.repository.test;

import build.jenesis.repository.server.ImportJobs;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A status read of a background import must be a single store round trip: {@link ImportJobs#status} answers presence
 * and the small job-state bytes from one {@code readVersioned}, never an {@code exists()} probe followed by a
 * {@code read()} (two round trips - a real second network call on an object-store backend - on the endpoint a
 * migration client polls). The counting store here demonstrates, not merely asserts, that a present job costs one
 * lookup and no separate existence probe, and an absent job the same, so the poll cannot regress to the double-probe
 * the W7.1 sweep removed.
 */
class ImportJobsStatusTest {

    @TempDir
    Path root;

    private CountingStore store() {
        return new CountingStore(ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null));
    }

    @Test
    void a_present_status_read_is_a_single_round_trip_with_no_existence_probe() throws IOException {
        CountingStore store = store();
        ImportJobs jobs = new ImportJobs();
        byte[] state = "{\"state\":\"running\",\"imported\":3}".getBytes(StandardCharsets.UTF_8);
        store.write("imports/job-1", new ByteArrayInputStream(state));
        store.reset();

        Optional<byte[]> status = jobs.status(store, "job-1");

        assertThat(status).as("the persisted job state is returned").isPresent();
        assertThat(new String(status.get(), StandardCharsets.UTF_8)).contains("\"imported\":3");
        assertThat(store.reads()).as("one store read serves the status").isEqualTo(1);
        assertThat(store.existsProbes()).as("no separate existence probe precedes the read").isZero();
    }

    @Test
    void an_absent_job_costs_one_lookup_and_no_probe() throws IOException {
        CountingStore store = store();
        ImportJobs jobs = new ImportJobs();

        assertThat(jobs.status(store, "missing")).as("an unknown job is empty").isEmpty();
        assertThat(store.reads()).as("an absent job is one lookup, not a probe then a read").isEqualTo(1);
        assertThat(store.existsProbes()).isZero();
    }

    /** Wraps a real filesystem store, counting the {@code readVersioned} and {@code exists} calls so the round-trip
     *  shape of a status read is demonstrable rather than assumed. */
    private static final class CountingStore implements ArtifactStore {

        private final ArtifactStore delegate;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger exists = new AtomicInteger();

        private CountingStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        void reset() {
            reads.set(0);
            exists.set(0);
        }

        int reads() {
            return reads.get();
        }

        int existsProbes() {
            return exists.get();
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            reads.incrementAndGet();
            return delegate.readVersioned(key);
        }

        @Override
        public boolean exists(String key) {
            exists.incrementAndGet();
            return delegate.exists(key);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
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
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
