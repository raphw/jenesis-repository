package build.jenesis.repository.server;

import module java.base;

/**
 * A thread-safe, <em>bounded</em> in-memory ring of the most recent {@link LogEntry log entries} - the recent-logs
 * viewer's store (WO.4). It is the stream-don't-buffer discipline made concrete: the {@link LogRingAppender} taps the
 * running logback context and pushes each event here, the oldest entry is evicted once {@link #capacity()} is reached,
 * and nothing ever re-reads a log file or grows without bound. Each entry carries a monotonic {@code seq} so a reader
 * tails by asking only for entries past the cursor it last saw.
 *
 * <p>Reads are served from a copied snapshot taken under the lock and filtered outside it, so a slow reader never
 * blocks the append path (a log event must never wait on a console query). The buffer degrades gracefully: an empty
 * buffer (nothing logged yet, or the appender not attached) answers every query with an empty list rather than an
 * error, and a non-positive configured capacity is clamped to at least one so the ring is always usable.
 */
public final class LogRingBuffer {

    /** The default number of entries retained when {@code jenesis.repository.logs.buffer} is unset. */
    public static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    private final ArrayDeque<LogEntry> entries;
    private final Object lock = new Object();
    private long nextSeq = 1;

    /** A ring of {@code capacity} most-recent entries; a capacity below one is clamped to one. */
    public LogRingBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new ArrayDeque<>(this.capacity);
    }

    /** The maximum number of entries retained before the oldest is evicted. */
    public int capacity() {
        return capacity;
    }

    /**
     * Record a captured event, assigning it the next monotonic {@code seq} and evicting the oldest entry if the ring is
     * full. The appender calls this on the logging thread, so it does only the O(1) enqueue/evict under the lock.
     *
     * @return the {@code seq} assigned to the stored entry.
     */
    public long record(Instant timestamp, String level, int levelValue, String logger, String message, String tenant) {
        synchronized (lock) {
            long seq = nextSeq++;
            if (entries.size() >= capacity) {
                entries.removeFirst();
            }
            entries.addLast(new LogEntry(seq, timestamp, level, levelValue, logger, message, tenant));
            return seq;
        }
    }

    /** The highest {@code seq} assigned so far (0 when nothing has been recorded) - the current tail cursor. */
    public long cursor() {
        synchronized (lock) {
            return nextSeq - 1;
        }
    }

    /** The number of entries currently retained (never more than {@link #capacity()}). */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /**
     * The retained entries matching every supplied filter, oldest first, capped to the last {@code limit}.
     *
     * @param minLevelValue the minimum logback level value ({@code null} for every level) - the level filter.
     * @param query         a case-insensitive substring the logger name or message must contain ({@code null}/blank
     *                      matches all) - the text search.
     * @param since         return only entries whose {@code seq} is strictly greater than this cursor ({@code null} or
     *                      negative for from the start) - the since-cursor for tailing.
     * @param tenant        {@code null}/blank returns every entry (the deployment-wide view); a non-blank value returns
     *                      only entries attributed to that tenant, excluding deployment-wide entries.
     * @param limit         the maximum number of (most recent) entries to return; clamped to at least one.
     */
    public List<LogEntry> recent(Integer minLevelValue, String query, Long since, String tenant, int limit) {
        List<LogEntry> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(entries);
        }
        long floor = since == null ? Long.MIN_VALUE : since;
        boolean tenantScoped = tenant != null && !tenant.isBlank();
        ArrayDeque<LogEntry> matched = new ArrayDeque<>();
        int cap = Math.max(1, limit);
        // Iterate newest-first so the "last N" cap keeps the most recent matches, then reverse to oldest-first output.
        ListIterator<LogEntry> it = snapshot.listIterator(snapshot.size());
        while (it.hasPrevious() && matched.size() < cap) {
            LogEntry entry = it.previous();
            if (entry.seq() <= floor) {
                continue;
            }
            if (minLevelValue != null && entry.levelValue() < minLevelValue) {
                continue;
            }
            if (!entry.matches(query)) {
                continue;
            }
            if (tenantScoped && !tenant.equals(entry.tenant())) {
                continue;
            }
            matched.addFirst(entry);
        }
        return List.copyOf(matched);
    }
}
