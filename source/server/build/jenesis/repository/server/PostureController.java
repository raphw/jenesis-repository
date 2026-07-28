package build.jenesis.repository.server;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.SecurityAdvisory;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import module java.base;

/**
 * The security-posture read - {@code GET /api/posture} (WO.5), the console / CLI / headless-agent read of the
 * deployment's configuration-warning advisories: every potentially-unsafe setting a discovered {@link
 * build.jenesis.repository.posture.SafetyAdvisor} raises against the effective configuration, collected once through
 * {@link PostureReport#discover} and returned severity-sorted (critical first). Each row names <em>why</em> a setting is
 * unsafe and the exact {@code jenesis.*} key/value that fixes it - it never repeats a read secret value, so this
 * surface (which enumerates the deployment's weaknesses) cannot itself leak one.
 *
 * <p>Registered as an explicit {@code @Bean} by {@link RepositoryAutoConfiguration}, reading the deployment
 * configuration off the Spring {@link Environment} (the same lookup {@code Features} installs). Read like every other
 * {@code /api} surface - key-auth'd ({@code repository:read}) by {@link RepositorySecurityAutoConfiguration}, read-only,
 * never an anonymous backdoor; a clean deployment returns an empty list. The enterprise edition mirrors this
 * independently as a superadmin-gated {@code /api/admin/posture}.
 */
@RestController
public final class PostureController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Environment environment;

    public PostureController(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @GetMapping("/api/posture")
    public void posture(HttpServletResponse response) throws IOException {
        PostureReport report = PostureReport.discover(Configuration.of(environment::getProperty));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SecurityAdvisory advisory : report.advisories()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", advisory.id());
            row.put("severity", advisory.severity().name());
            row.put("scope", advisory.scope().name());
            row.put("tenant", advisory.tenant());
            row.put("title", advisory.title());
            row.put("why", advisory.why());
            row.put("fix", advisory.fix());
            row.put("settingKey", advisory.settingKey());
            row.put("settingValue", advisory.settingValue());
            row.put("docs", advisory.docs());
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", report.count());
        body.put("critical", report.count(build.jenesis.repository.posture.Severity.CRITICAL));
        body.put("warn", report.count(build.jenesis.repository.posture.Severity.WARN));
        body.put("info", report.count(build.jenesis.repository.posture.Severity.INFO));
        body.put("advisories", rows);
        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
