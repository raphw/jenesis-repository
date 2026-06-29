package build.jenesis.repository;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in last-used tracking for credentials, off the request path on its own daemon thread. An allowed request
 * offers a {@link Hit} (its tenant and the key's hash) to a bounded in-memory queue (non-blocking, dropped if
 * saturated - last-used is an informational signal, not an audit log); the thread drains the queue and stamps each
 * key's last-used through {@link Authorization#recordUsed} at most once per day (day granularity is all a management
 * view needs). {@link #drain} is public so it can be driven synchronously, without the thread. A {@link #record} is
 * a no-op when tracking is off.
 */
public final class KeyUsageTracker implements AutoCloseable {

    /** A use worth recording: the tenant the key carries and the key's SHA-256 hash. */
    public record Hit(String tenant, String hash) {
    }

    private final Authorization authorization;
    private final boolean enabled;
    private final BlockingQueue<Hit> queue = new LinkedBlockingQueue<>(100_000);
    private final Map<String, LocalDate> writtenDay = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread thread;

    public KeyUsageTracker(Authorization authorization, boolean enabled) {
        this.authorization = authorization;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Offer a use for tracking - non-blocking, a no-op when tracking is off, dropped if the queue is full. */
    public void record(String tenant, String hash) {
        if (enabled && tenant != null && hash != null) {
            queue.offer(new Hit(tenant, hash));
        }
    }

    public void start() {
        if (!enabled) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "jenesis-repository-key-usage");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
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

    /** Stamp each unique hit's last-used time, at most once per day per credential. */
    public void drain(Collection<Hit> batch, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (Hit hit : new LinkedHashSet<>(batch)) {
            String key = hit.tenant() + "/" + hit.hash();
            if (today.equals(writtenDay.get(key))) {
                continue;
            }
            try {
                authorization.recordUsed(hit.tenant(), hit.hash(), now);
                writtenDay.put(key, today);
            } catch (IOException e) {
                // best-effort
            }
        }
    }
}
