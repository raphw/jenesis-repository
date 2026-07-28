package build.jenesis.repository.posture.test;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;

import java.util.List;

/**
 * A {@code provides}-declared {@link SafetyAdvisor} standing in for a real feature module, so the {@link
 * build.jenesis.repository.posture.PostureReport#discover ServiceLoader discovery} has something to find. It raises one
 * advisory when its toy key is set, and (like a disabled module) nothing when it is not.
 */
public final class SampleSafetyAdvisor implements SafetyAdvisor {

    /** The key a test flips to prove the advisor fires and that a report discovers it. */
    public static final String KEY = "jenesis.sample.unsafe";

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        if (!config.flag(KEY, false)) {
            return List.of();
        }
        return List.of(SecurityAdvisory.deployment("jenesis.sample.unsafe", Severity.INFO,
                "The sample feature is in its unsafe demo mode",
                "The sample feature is running in a demonstration mode that is not meant for production.",
                "Turn the sample feature's unsafe mode off.",
                KEY, "false", "https://jenesis.build/docs/security/posture#jenesis.sample.unsafe"));
    }
}
