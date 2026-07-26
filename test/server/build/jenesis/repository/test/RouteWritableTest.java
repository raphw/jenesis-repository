package build.jenesis.repository.test;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write branch of {@link RepositoryController#handle} answers {@code 405} on a write (PUT/POST/PATCH/DELETE) to a
 * route that is not a valid write target ({@link RepositoryRouting.Route#writable()}==false), and lets a write to a
 * writable route proceed - the seam a multi-tenant routing plugs a read-only repository into without a fork. The
 * controller is driven directly with a routing double that resolves a writable-or-not route and lightweight
 * servlet-request/response proxies, so the branch is asserted without booting the whole server. A writable route is
 * proven to proceed past the gate by dispatching over an empty format set, which leaves the unclaimed write a
 * {@code 404} (not the {@code 405} a non-writable route short-circuits to).
 */
public class RouteWritableTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    /** A routing double that resolves every request to one fixed route of the configured writability. */
    private record FixedRoute(RepositoryRouting.Route route) implements RepositoryRouting {
        @Override
        public Route route(HttpServletRequest request) {
            return route;
        }
    }

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
    }

    private RepositoryController controller(boolean writable) {
        RepositoryRouting.Route route = new RepositoryRouting.Route("default", "default", store, "/nope/x", writable);
        FormatDispatcher empty = new FormatDispatcher(List.of(), Map.of(), ProxyFormat.Fetcher.NONE);
        return new RepositoryController(new FixedRoute(route), empty, List.of(), ProxyFormat.Fetcher.NONE);
    }

    /** The captured status the controller set on the response proxy. */
    private static final class Status {
        int value = -1;
    }

    private static HttpServletRequest request(String method) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                RouteWritableTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, invoked, args) -> "getMethod".equals(invoked.getName())
                        ? method : defaultValue(invoked.getReturnType()));
    }

    private static HttpServletResponse response(Status status) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                RouteWritableTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, invoked, args) -> {
                    if ("setStatus".equals(invoked.getName())) {
                        status.value = (int) args[0];
                        return null;
                    }
                    return defaultValue(invoked.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }

    @Test
    void a_write_to_a_non_writable_route_is_405() throws Exception {
        Status status = new Status();
        controller(false).handle(request("PUT"), response(status));
        assertThat(status.value).as("a write to a non-writable route is 405 before any layout").isEqualTo(405);
    }

    @Test
    void a_delete_to_a_non_writable_route_is_405() throws Exception {
        Status status = new Status();
        controller(false).handle(request("DELETE"), response(status));
        assertThat(status.value).as("a DELETE is a write too - 405 on a non-writable route").isEqualTo(405);
    }

    @Test
    void a_write_to_a_writable_route_proceeds_past_the_gate() throws Exception {
        Status status = new Status();
        controller(true).handle(request("PUT"), response(status));
        assertThat(status.value).as("a writable route proceeds; the empty format set leaves the write an unclaimed 404, not a 405")
                .isEqualTo(404);
    }

    @Test
    void a_read_to_a_non_writable_route_is_not_gated() throws Exception {
        Status status = new Status();
        controller(false).handle(request("GET"), response(status));
        assertThat(status.value).as("a read is not a write - the writable gate never fires, the unclaimed read is 404")
                .isEqualTo(404);
    }
}
