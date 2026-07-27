package build.jenesis.repository.server;

import module java.base;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * The logback tap that feeds the {@link LogRingBuffer}: an {@link AppenderBase} attached to the running logback root
 * logger so every {@code info}/{@code warn}/{@code error} the JVM emits through slf4j is captured into the bounded ring
 * without ever re-reading a log file (WO.4, the stream-don't-buffer rule). Each event contributes its level, timestamp,
 * logger name and already-formatted message; the tenant is read from the MDC key {@value #TENANT_MDC_KEY} when the
 * request path threaded one, and is left {@code null} - a deployment-wide entry - when it did not. The appender does
 * only an O(1) push per event onto the ring, so it adds no measurable cost to the logging path and cannot grow memory
 * without bound.
 *
 * <p>Attached programmatically at startup ({@code RepositoryAutoConfiguration.logRingAppender}) rather than through a
 * {@code logback.xml}, so the ring is present when the server runs and nothing is attached when it is not.
 */
public final class LogRingAppender extends AppenderBase<ILoggingEvent> {

    /** The MDC key an entry's tenant is read from when the request threaded one; absent means a deployment-wide entry. */
    public static final String TENANT_MDC_KEY = "tenant";

    private final LogRingBuffer buffer;

    public LogRingAppender(LogRingBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        setName("jenesis-recent-logs");
    }

    /** The ring this appender feeds - the same instance the {@code /api/logs} read serves from. */
    public LogRingBuffer buffer() {
        return buffer;
    }

    /** The logback level value a level-name filter maps to, or {@code null} for no filter (unset, blank or an
     *  unrecognized name - an unknown filter never silently drops everything, it simply does not filter). The read
     *  path calls this so it needs no logback dependency of its own. */
    public static Integer levelValue(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        Level parsed = Level.toLevel(level.trim(), null);
        return parsed == null ? null : parsed.toInt();
    }

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String tenant = mdc == null ? null : mdc.get(TENANT_MDC_KEY);
        buffer.record(
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(),
                event.getLevel().toInt(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                tenant == null || tenant.isBlank() ? null : tenant);
    }
}
