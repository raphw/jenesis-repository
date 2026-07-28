package build.jenesis.repository.test;

import build.jenesis.repository.server.CapabilityContributor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A test {@link CapabilityContributor} registered through this test module's {@code module-info} {@code provides}
 * clause, so the running free server discovers it via {@link java.util.ServiceLoader} exactly as a richer distribution
 * would - proving that {@code /api/capabilities} merges a contributor's data through the SPI without a bean override
 * (WFE.1). It contributes distinctively-namespaced keys ({@code testFormats}, {@code testModuleFlag}) that do not
 * collide with the free base map ({@code readOnly}, {@code auth}, {@code anonymousRights}), mirroring the shape the
 * enterprise contributor will use for its formats / import-sources / module-flags.
 */
public class TestCapabilityContributor implements CapabilityContributor {

    /** The marker key the endpoint test looks for to prove the contribution was merged. */
    public static final String MARKER_KEY = "testFormats";

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put(MARKER_KEY, List.of("maven", "docker", "raw"));
        extra.put("testModuleFlag", true);
        return extra;
    }
}
