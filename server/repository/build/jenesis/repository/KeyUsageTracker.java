package build.jenesis.repository;

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
 */
public final class KeyUsageTracker implements AutoCloseable {

    /** A use worth recording: the tenant the key carries, the key's SHA-256 hash and the request's source address. */
    public record Hit(String tenant, String hash, String address) {
    }

    private static final class Pending {
        private long count;
        private long flushed;
        private String address;
        private Instant when;
    }

    private final Authorization authorization;
    private final boolean enabled;
    private final BlockingQueue<Hit> queue = new LinkedBlockingQueue<>(100_000);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> writtenDay = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    private Thread thread;

    public KeyUsageTracker(Authorization authorization, boolean enabled) {
        this.authorization = authorization;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Whether the worker thread is started and alive; an enabled tracker whose thread has died is unhealthy. */
    public boolean alive() {
        return thread != null && thread.isAlive();
    }

    /** Uses dropped because the in-memory queue was saturated - a back-pressure signal surfaced on health. */
    public long dropped() {
        return dropped.get();
    }

    /** Offer a use for tracking - non-blocking, a no-op when tracking is off, counted as dropped if the queue is full. */
    public void record(String tenant, String hash, String address) {
        if (enabled && tenant != null && hash != null && !queue.offer(new Hit(tenant, hash, address))) {
            dropped.incrementAndGet();
        }
    }

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
    }

    private void flush(String key, Pending entry, LocalDate day) {
        long delta = entry.count - entry.flushed;
        if (delta <= 0) {
            return;
        }
        int slash = key.indexOf('/');
        try {
            authorization.recordUsed(key.substring(0, slash), key.substring(slash + 1), entry.when, entry.address, delta);
            entry.flushed = entry.count;
            writtenDay.put(key, day);
        } catch (IOException e) {
            // best-effort
        }
    }
}
