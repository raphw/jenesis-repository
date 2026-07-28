package build.jenesis.repository.ui;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Publishes deployment-wide flags every console view reads to the model, so a template shows them without each
 * controller repeating the lookup. It is the read-only flag ({@code jenesis.repository.read-only}, env
 * {@code JENESIS_REPOSITORY_READ_ONLY}): when set, the console renders a read-only banner (and a mutating affordance
 * can hide itself with the same attribute); and the security-posture count (WO.5): the number of configuration-warning
 * advisories the discovered {@link build.jenesis.repository.posture.SafetyAdvisor}s raise against the effective
 * configuration, which the header shows as a badge linking to the Security-posture panel. Both are read straight off the
 * {@link Environment} - the console does no store write of its own, so it needs no bound configuration bean to observe
 * the deployment's mode.
 */
@ControllerAdvice
public class ConsoleAdvice {

    private final Environment environment;

    public ConsoleAdvice(Environment environment) {
        this.environment = environment;
    }

    @ModelAttribute("readOnly")
    public boolean readOnly() {
        return environment.getProperty("jenesis.repository.read-only", Boolean.class, false);
    }

    /** The number of security-posture advisories the deployment currently raises - the count the header badge shows;
     *  zero renders no badge. Collected once through {@link PostureReport#discover} over the effective configuration. */
    @ModelAttribute("postureCount")
    public int postureCount() {
        return PostureReport.discover(Configuration.of(environment::getProperty)).count();
    }
}
