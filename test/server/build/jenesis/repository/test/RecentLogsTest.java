package build.jenesis.repository.test;

import build.jenesis.repository.server.LogEntry;
import build.jenesis.repository.server.LogRingAppender;
import build.jenesis.repository.server.LogRingBuffer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recent-logs ring and its logback tap (WO.4, the free-core mirror of the enterprise viewer): the unit proofs that
 * the bounded ring captures and evicts, that the level / text / since-cursor / tenant filters select correctly, that
 * the appender maps a real logback event (including the MDC tenant tag) into an entry, and that an empty ring degrades
 * gracefully. The {@code GET /api/logs} authorization gating is pinned by {@link RecentLogsE2ETest}.
 */
class RecentLogsTest {

    private static final int INFO = Level.INFO.toInt();
    private static final int WARN = Level.WARN.toInt();
    private static final int ERROR = Level.ERROR.toInt();

    private static long record(LogRingBuffer ring, int levelValue, String logger, String message, String tenant) {
        String name = levelValue >= ERROR ? "ERROR" : levelValue >= WARN ? "WARN" : "INFO";
        return ring.record(Instant.now(), name, levelValue, logger, message, tenant);
    }

    @Test
    void the_ring_is_bounded_and_evicts_the_oldest_past_capacity() {
        LogRingBuffer ring = new LogRingBuffer(3);
        for (int i = 1; i <= 5; i++) {
            record(ring, INFO, "app", "entry " + i, null);
        }
        assertThat(ring.size()).as("never grows past capacity").isEqualTo(3);
        assertThat(ring.recent(null, null, null, null, 100)).extracting(LogEntry::message)
                .as("only the three most recent survive").containsExactly("entry 3", "entry 4", "entry 5");
        assertThat(ring.cursor()).isEqualTo(5L);
    }

    @Test
    void a_non_positive_capacity_is_clamped_so_the_ring_is_always_usable() {
        LogRingBuffer ring = new LogRingBuffer(0);
        assertThat(ring.capacity()).isEqualTo(1);
        record(ring, INFO, "app", "a", null);
        record(ring, INFO, "app", "b", null);
        assertThat(ring.recent(null, null, null, null, 100)).extracting(LogEntry::message).containsExactly("b");
    }

    @Test
    void the_level_filter_returns_entries_at_that_level_or_higher() {
        LogRingBuffer ring = new LogRingBuffer(10);
        record(ring, INFO, "app", "info one", null);
        record(ring, WARN, "app", "warn one", null);
        record(ring, ERROR, "app", "error one", null);
        assertThat(ring.recent(WARN, null, null, null, 100)).extracting(LogEntry::message)
                .as("WARN filter keeps WARN and ERROR, drops INFO").containsExactly("warn one", "error one");
        assertThat(ring.recent(null, null, null, null, 100)).hasSize(3);
    }

    @Test
    void the_text_search_matches_the_logger_or_the_message_case_insensitively() {
        LogRingBuffer ring = new LogRingBuffer(10);
        record(ring, INFO, "build.jenesis.proxy", "spool draining", null);
        record(ring, INFO, "build.jenesis.store", "wrote object", null);
        record(ring, WARN, "build.jenesis.store", "SPOOL backpressure", null);
        assertThat(ring.recent(null, "spool", null, null, 100)).extracting(LogEntry::message)
                .as("matches message text, case-insensitively").containsExactly("spool draining", "SPOOL backpressure");
        assertThat(ring.recent(null, "proxy", null, null, 100)).extracting(LogEntry::message)
                .as("matches the logger name too").containsExactly("spool draining");
    }

    @Test
    void the_since_cursor_returns_only_entries_past_it_for_tailing() {
        LogRingBuffer ring = new LogRingBuffer(10);
        for (int i = 1; i <= 5; i++) {
            record(ring, INFO, "app", "entry " + i, null);
        }
        assertThat(ring.recent(null, null, 3L, null, 100)).extracting(LogEntry::seq).containsExactly(4L, 5L);
        assertThat(ring.recent(null, null, 5L, null, 100)).as("nothing new past the tail").isEmpty();
    }

    @Test
    void tenant_attribution_filters_and_deployment_wide_entries_are_excluded_by_a_tenant_scope() {
        LogRingBuffer ring = new LogRingBuffer(10);
        record(ring, INFO, "app", "acme one", "acme");
        record(ring, INFO, "app", "deployment wide", null);
        record(ring, WARN, "app", "beta one", "beta");
        assertThat(ring.recent(null, null, null, "acme", 100)).extracting(LogEntry::message)
                .as("a tenant scope returns only that tenant's attributable entries").containsExactly("acme one");
        assertThat(ring.recent(null, null, null, null, 100)).extracting(LogEntry::message)
                .as("no scope is the deployment-wide view of everything")
                .containsExactly("acme one", "deployment wide", "beta one");
    }

    @Test
    void an_empty_ring_degrades_gracefully_to_an_empty_read() {
        assertThat(new LogRingBuffer(10).recent(null, null, null, null, 100)).isEmpty();
    }

    @Test
    void the_appender_captures_a_real_logback_event_and_its_mdc_tenant_into_the_ring() {
        LogRingBuffer ring = new LogRingBuffer(100);
        LogRingAppender appender = new LogRingAppender(ring);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger("build.jenesis.recentlogs.test");
        logger.setLevel(Level.ALL);
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        try {
            logger.info("plain info");
            MDC.put(LogRingAppender.TENANT_MDC_KEY, "acme");
            try {
                logger.warn("tenant warning");
            } finally {
                MDC.remove(LogRingAppender.TENANT_MDC_KEY);
            }
            logger.error("boom {}", "detail");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        List<LogEntry> captured = ring.recent(null, null, null, null, 100);
        assertThat(captured).extracting(LogEntry::message).containsExactly("plain info", "tenant warning", "boom detail");
        assertThat(captured).extracting(LogEntry::level).containsExactly("INFO", "WARN", "ERROR");
        assertThat(captured.get(0).tenant()).as("no MDC tenant -> deployment-wide").isNull();
        assertThat(captured.get(1).tenant()).as("the MDC tenant is captured").isEqualTo("acme");
        // The static level-name mapping the read path uses.
        assertThat(LogRingAppender.levelValue("WARN")).isEqualTo(WARN);
        assertThat(LogRingAppender.levelValue("nonsense")).as("an unknown level does not filter").isNull();
    }
}
