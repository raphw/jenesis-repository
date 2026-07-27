package build.jenesis.repository.server;

import module java.base;

/**
 * One captured log record held in the {@link LogRingBuffer}: a monotonic {@code seq} (the tailing cursor a reader
 * advances past), the wall-clock {@code timestamp}, the log {@code level} name and its numeric {@code levelValue}
 * (logback's {@code Level.toInt()}, so a level filter is a single {@code >=} without re-parsing the name), the
 * {@code logger} name, the already-formatted {@code message}, and the {@code tenant} the entry is attributable to
 * ({@code null} for a deployment-wide entry - the appender reads the tenant from the MDC when the request threaded one,
 * and leaves it {@code null} otherwise). A value record: the buffer stores exactly what the appender captured, the
 * {@code GET /api/logs} read renders it, and nothing re-reads a file.
 *
 * <p>The free core's recent-logs viewer (WO.4) mirrors the enterprise implementation independently - a parallel,
 * consistent design in each repo's own modules, not a shared module.
 */
public record LogEntry(long seq, Instant timestamp, String level, int levelValue, String logger, String message,
                       String tenant) {

    public LogEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(message, "message");
    }

    /** Whether this entry is attributable to a specific tenant (the appender captured a tenant tag) rather than being a
     *  deployment-wide record. */
    public boolean tenantAttributable() {
        return tenant != null;
    }

    /** Whether this entry's logger name or message contains {@code query}, case-insensitively - the text search the
     *  read applies. A blank query matches everything. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return message.toLowerCase(Locale.ROOT).contains(needle)
                || logger.toLowerCase(Locale.ROOT).contains(needle);
    }
}
