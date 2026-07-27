package build.jenesis.repository.server;

import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import module java.base;

/**
 * The recent-logs tail - {@code GET /api/logs}, the console / CLI / API's read of the instance's most recent log
 * entries from the bounded in-memory {@link LogRingBuffer ring} a logback appender feeds (WO.4, never re-reading a file,
 * never unbounded). Supports a {@code level} filter (entries at that level or higher), a {@code q} case-insensitive
 * text search over the logger name and message, a {@code since} cursor a tailing reader passes back (the response
 * carries the current {@code cursor}) to fetch only what is new, an optional {@code tenant} scope, and a {@code limit}.
 *
 * <p>Registered as an explicit {@code @Bean} by {@link RepositoryAutoConfiguration} beside {@link RepositoryController}
 * (Spring MVC maps its handler on the bean), reading the same {@link LogRingBuffer} the {@link LogRingAppender} feeds.
 * Read like every other {@code /api} surface - key-auth'd ({@code repository:read}) by
 * {@link RepositorySecurityAutoConfiguration}, so it is not an open backdoor; an empty ring returns an empty list
 * rather than an error. The enterprise edition mirrors this independently as an operator-gated {@code /api/admin/logs}.
 */
@RestController
public final class RecentLogsController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The default number of entries a read returns when {@code limit} is not supplied. */
    static final int DEFAULT_LIMIT = 200;

    private final LogRingBuffer buffer;

    public RecentLogsController(LogRingBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    @GetMapping("/api/logs")
    public void logs(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Integer minLevel = LogRingAppender.levelValue(request.getParameter("level"));
        List<LogEntry> entries = buffer.recent(minLevel, request.getParameter("q"),
                longParam(request.getParameter("since")), request.getParameter("tenant"),
                intParam(request.getParameter("limit")));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LogEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", entry.seq());
            row.put("timestamp", entry.timestamp().toString());
            row.put("level", entry.level());
            row.put("logger", entry.logger());
            row.put("message", entry.message());
            row.put("tenant", entry.tenant());
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cursor", buffer.cursor());
        body.put("count", entries.size());
        body.put("entries", rows);
        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }

    /** A {@code long} query parameter, or {@code null} when unset or unparseable (the since-cursor is then from the
     *  start). */
    private static Long longParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** The {@code limit} query parameter, or {@link #DEFAULT_LIMIT} when unset or unparseable. */
    private static int intParam(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException _) {
            return DEFAULT_LIMIT;
        }
    }
}
