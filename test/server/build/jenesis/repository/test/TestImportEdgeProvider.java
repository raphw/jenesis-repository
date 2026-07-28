package build.jenesis.repository.test;

import build.jenesis.repository.server.ImportEdgeProvider;

import module java.base;

/**
 * A test {@link ImportEdgeProvider} registered through this test module's {@code module-info} {@code provides} clause,
 * so the running free server discovers it via {@link java.util.ServiceLoader} exactly as a richer distribution would -
 * proving the free import edge ({@code /repository/admin/import}) yields when a distribution owns the edge, without a
 * {@code WebMvcRegistrations} mapping-suppression (WFE.1).
 *
 * <p>It is <em>inert by default</em>: it declares a required-config key ({@link #ACTIVATION_KEY}) that no other test
 * sets, so under the shared {@code Features} convention it {@linkplain build.jenesis.repository.store.Features#active
 * self-disables} at discovery and the free import edge is served exactly as today for every other import test. The
 * yield test sets {@link #ACTIVATION_KEY} before boot to activate it, so <em>only</em> that test sees the free edge
 * yield. An enterprise provider would instead need no config and claim the edge on presence alone; the required-config
 * gate here is purely to keep the suppressing provider from affecting the rest of the suite.
 */
public class TestImportEdgeProvider implements ImportEdgeProvider {

    /** The property this provider requires to become active - unset by default, so the provider is inert; the yield
     *  test sets it to prove the free import edge yields when a distribution owns the edge. */
    public static final String ACTIVATION_KEY = "jenesis.import-edge.test-active";

    @Override
    public String name() {
        return "test-import-edge";
    }

    @Override
    public Set<String> requiredConfig() {
        return Set.of(ACTIVATION_KEY);
    }
}
