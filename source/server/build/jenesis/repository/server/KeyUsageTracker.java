package build.jenesis.repository.server;

/**
 * Opt-in usage tracking for credentials: an allowed request offers its tenant, key hash and source address, and an
 * implementation stamps the credential's last use and count through {@link Authorization#recordUsed}, off the
 * request path. How hits are batched and flushed is the implementation's part, supplied by a
 * {@link KeyUsageTrackerProvider} module discovered with {@link java.util.ServiceLoader}; with none installed
 * {@link #NONE} stands in - nothing records, and a health indicator reports the worker as off. Usage is an
 * informational signal, not an audit log: {@link #record} must never block or fail the request it observes.
 */
public interface KeyUsageTracker extends AutoCloseable {

    /** Whether tracking is switched on; {@link #record} is a no-op when it is not. */
    boolean enabled();

    /** Whether the worker is started and alive; an enabled tracker whose worker has died is unhealthy. */
    boolean alive();

    /** Uses dropped under back-pressure - surfaced on health. */
    long dropped();

    /** Offer a use for tracking - non-blocking, best-effort, a no-op when tracking is off. */
    void record(String tenant, String hash, String address);

    void start();

    @Override
    void close();

    /** The shared tracker standing in when no usage module is installed: it records nothing. A singleton, so a
     *  composition can tell "not installed" by identity. */
    KeyUsageTracker NONE = new KeyUsageTracker() {

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public boolean alive() {
            return false;
        }

        @Override
        public long dropped() {
            return 0L;
        }

        @Override
        public void record(String tenant, String hash, String address) {
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }
    };
}
