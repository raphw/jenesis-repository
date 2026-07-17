package build.jenesis.repository.usage;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.server.Authorization;
import build.jenesis.repository.server.KeyUsageTracker;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in usage tracking for credentials, off the request path on its own worker thread - started and stopped
 * through Spring's bean lifecycle (not a daemon), so {@link #close} interrupts and joins it for a clean shutdown,
 * and {@link #alive}/{@link #dropped} let a health indicator watch it. An allowed request offers a
 * {@link Hit} (its tenant, the key's hash and the source address) to a bounded in-memory queue (non-blocking, dropped
 * if saturated - usage is an informational signal, not an audit log); the thread drains the queue into a per-credential
 * accumulator that counts every hit and remembers the last address, and flushes each credential through {@link
 * Authorization#recordUsed} at most once per day (the persisted count therefore lags but converges, and the store sees
 * at most one write per credential per day). The flush adds the delta since the last flush, so no hit within a process
 * lifetime is lost; a crash forfeits only the unflushed tail, which an informational counter can bear. {@link #drain}
 * is public so it can be driven synchronously, without the thread. A {@link #record} is a no-op when tracking is off.
 *
 * <p>An enabled tracker is its own {@link ObservabilitySource}: it reports its bounded queue depth ({@code
 * jenesis.usage.queue}, used vs the fixed capacity - the saturation that turns into drops), the per-credential
 * accumulators it holds ({@code jenesis.usage.tracked}), the hits it has dropped under back-pressure ({@code
 * jenesis.usage.dropped}), a {@code jenesis.usage.worker} health check (DOWN when the worker died with tracking on)
 * and a {@code jenesis.usage.flush} task status stamped with the last drain. A <em>disabled</em> tracker (tracking
 * switched off) reports nothing at all, consistent with the "a disabled plugin is not listed" rule; the same
 * distinction the health surface already draws between "installed but off" and a dead worker.
 */
public final class BatchingKeyUsageTracker implements KeyUsageTracker, ObservabilitySource {

    /** A use worth recording: the tenant the key carries, the key's SHA-256 hash and the request's source address. */
    public record Hit(String tenant, String hash, String address) {
    }

    private static final class Pending {
        private long count;
        private long flushed;
        private String address;
        private Instant when;
    }

    /** The bounded queue depth: past it a hit is dropped rather than blocking a request, and this is the ceiling the
     *  {@code jenesis.usage.queue} used-vs-available metric measures against. */
    private static final int QUEUE_CAPACITY = 100_000;

    private final Authorization authorization;
    private final boolean enabled;
    private final BlockingQueue<Hit> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> writtenDay = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    private volatile Instant lastDrain;
    private Thread thread;

    public BatchingKeyUsageTracker(Authorization authorization, boolean enabled) {
        this.authorization = authorization;
        this.enabled = enabled;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    /** Whether the worker thread is started and alive; an enabled tracker whose thread has died is unhealthy. */
    @Override
    public boolean alive() {
        return thread != null && thread.isAlive();
    }

    /** Uses dropped because the in-memory queue was saturated - a back-pressure signal surfaced on health. */
    @Override
    public long dropped() {
        return dropped.get();
    }

    /** Offer a use for tracking - non-blocking, a no-op when tracking is off, counted as dropped if the queue is full. */
    @Override
    public void record(String tenant, String hash, String address) {
        if (enabled && tenant != null && hash != null && !queue.offer(new Hit(tenant, hash, address))) {
            dropped.incrementAndGet();
        }
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "jenesis-repository-key-usage");
        thread.start();
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LocalDate today = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            flush(entry.getKey(), entry.getValue(), today);
        }
    }

    private void loop() {
        while (running) {
            try {
                Hit first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    List<Hit> batch = new ArrayList<>();
                    batch.add(first);
                    queue.drainTo(batch);
                    drain(batch, Instant.now());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // keep the worker alive across a failing iteration
            }
        }
    }

    /** Accumulate each hit into its credential's running count and last address, then flush each credential whose
     *  delta has not been written today, at most one store write per credential per day. */
    public void drain(Collection<Hit> batch, Instant now) {
        for (Hit hit : batch) {
            Pending entry = pending.computeIfAbsent(hit.tenant() + "/" + hit.hash(), key -> new Pending());
            entry.count++;
            entry.when = now;
            if (hit.address() != null) {
                entry.address = hit.address();
            }
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            if (!today.equals(writtenDay.get(entry.getKey()))) {
                flush(entry.getKey(), entry.getValue(), today);
            }
        }
        // Drop a fully-flushed credential not seen today: its delta is already persisted, so a later hit rebuilds a
        // fresh Pending (flushed=0) and flushes the new delta correctly. Without this the maps grow one entry per
        // (tenant, credential-hash) ever seen - a credential-rotating or anonymous-authorized tenant leaks memory and
        // slows each drain (it scans the whole map). The sibling download tracker bounds itself the same way.
        pending.entrySet().removeIf(entry -> {
            Pending value = entry.getValue();
            if (value.count == value.flushed && !today.equals(writtenDay.get(entry.getKey()))) {
                writtenDay.remove(entry.getKey());
                return true;
            }
            return false;
        });
        lastDrain = now;
    }

    /** The number of per-credential accumulators currently held: fully-flushed idle entries are dropped each drain,
     *  so this is bounded by the credentials seen in the current day (plus any carrying an unflushed delta), not every
     *  credential ever seen - a health/scale read can assert the map does not grow across day boundaries. */
    public int tracked() {
        return pending.size();
    }

    @Override
    public List<Metric> metrics() {
        if (!enabled) {
            return List.of();
        }
        return List.of(
                Metric.bounded("jenesis.usage.queue",
                        "Credential-use hits buffered off the request path waiting for the worker to drain them, "
                                + "against the fixed queue bound past which a hit is dropped rather than blocking a request.",
                        queue.size(), QUEUE_CAPACITY, "hits"),
                Metric.gauge("jenesis.usage.tracked",
                        "Per-credential accumulators currently held - bounded by the credentials seen in the current "
                                + "UTC day (plus any carrying an unflushed delta), not every credential ever seen.",
                        tracked(), ""),
                Metric.counter("jenesis.usage.dropped",
                        "Credential-use hits dropped because the in-memory queue was saturated - back-pressure, not "
                                + "an outage; usage is an informational signal, never an audit log.",
                        dropped(), "hits"));
    }

    @Override
    public List<HealthCheck> healthChecks() {
        if (!enabled) {
            return List.of();
        }
        String description = "Credential-usage worker thread is started and draining hits off the request path.";
        return List.of(alive()
                ? HealthCheck.up("jenesis.usage.worker", description)
                : HealthCheck.of("jenesis.usage.worker", description, Health.DOWN,
                        "usage tracking is switched on but its worker thread is not running"));
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        if (!enabled) {
            return List.of();
        }
        boolean alive = alive();
        return List.of(TaskStatus.ran("jenesis.usage.flush",
                "Background worker draining buffered credential-use hits and flushing each credential's running "
                        + "count and last address through the authorization store at most once per UTC day.",
                alive ? TaskStatus.State.RUNNING : TaskStatus.State.FAILED, lastDrain, null,
                alive ? "draining the usage queue and flushing each credential at most once per UTC day"
                        : "worker thread is not running"));
    }

    private void flush(String key, Pending entry, LocalDate day) {
        long delta = entry.count - entry.flushed;
        if (delta <= 0) {
            return;
        }
        int slash = key.indexOf('/');
        try {
            if (authorization.recordUsed(key.substring(0, slash), key.substring(slash + 1),
                    entry.when, entry.address, delta)) {
                entry.flushed = entry.count;
                writtenDay.put(key, day);
            }
            // A false return means every compare-and-set lost to contention: leave `flushed` where it is so the delta
            // is re-attempted on the next flush rather than being marked written and silently dropped.
        } catch (IOException e) {
            // best-effort
        }
    }
}
