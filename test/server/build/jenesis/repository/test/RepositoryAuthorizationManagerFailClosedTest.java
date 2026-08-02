package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fail-closed proof for {@link RepositoryAuthorizationManager}: when the authorization store read that
 * {@code authorize()} performs (the source-IP allowlist / grant lookup) fails with an {@link IOException}, the
 * request is <em>denied</em> - a {@code FORBIDDEN} decision that the entry point answers {@code 403} - never allowed.
 * A store outage or a transient read error is the one moment a security gate is most tempted to fail open ("let it
 * through, the store is flaky"); the credential model instead treats an unreadable store as no proof of authority and
 * refuses the request. The manager is driven directly with an {@link Authorization} over a store double whose read
 * throws, a routing double and a Mockito servlet-request mock (the {@code RouteWritableTest} idiom - the server
 * component under test without booting the whole server), so the {@code catch (IOException) -> FORBIDDEN} branch is
 * asserted in isolation, which no end-to-end test drives today.
 *
 * <p>A control over a clean store where the same well-formed key is provisioned with the required right proves the
 * wiring returns a granted {@code ALLOWED} on a successful read - so the fault, not a blanket deny, is what flips the
 * verdict to a fail-closed {@code FORBIDDEN}.
 */
public class RepositoryAuthorizationManagerFailClosedTest {

    @TempDir
    Path root;

    /** A routing double that resolves every request to one fixed route, exactly as {@code RouteWritableTest} does; the
     *  manager only reads {@link RepositoryRouting.Route#path()} off it. */
    private record FixedRoute(RepositoryRouting.Route route) implements RepositoryRouting {
        @Override
        public Route route(HttpServletRequest request) {
            return route;
        }
    }

    /** A store whose {@code readVersioned} always fails with an {@link IOException}, standing in for a store outage or
     *  a transient read error on the authorization lookup; every other operation is unreachable in this path. */
    private static final class UnreadableStore implements ArtifactStore {
        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            throw new IOException("store read failed");
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public boolean exists(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void read(String key, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream open(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String key, InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> list(String prefix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            throw new UnsupportedOperationException();
        }
    }

    private static HttpServletRequest request(String key, Map<String, Object> attributes) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/repository/maven/org/x/y/1/y-1.jar");
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(key);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(request).setAttribute(anyString(), any());
        return request;
    }

    private RepositoryAuthorizationManager manager(Authorization authorization) {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        RepositoryRouting.Route route = new RepositoryRouting.Route("acme", "default", store, "maven/org/x/y/1/y-1.jar");
        return new RepositoryAuthorizationManager(authorization, new FixedRoute(route));
    }

    @Test
    void a_store_read_error_during_authorize_denies_fail_closed() {
        Authorization authorization = Authorization.enforcing(new UnreadableStore());
        String key = Authorization.mint("acme");   // a well-formed key, so authorize() reaches the failing store read
        Map<String, Object> attributes = new HashMap<>();

        var result = manager(authorization).authorize(() -> null, new RequestAuthorizationContext(request(key, attributes)));

        assertThat(result.isGranted())
                .as("an unreadable store denies the request - the gate fails closed, never open").isFalse();
        assertThat(attributes.get("jenesis.repository.decision"))
                .as("and specifically FORBIDDEN, which the entry point answers 403 - not a 401 or, worse, an allow")
                .isEqualTo(Authorization.Decision.FORBIDDEN);
    }

    @Test
    void the_same_request_is_allowed_when_the_store_reads_cleanly() throws IOException {
        // The control: over a clean store where the key carries repository:read, the identical request is allowed. So
        // the fail-closed deny above is the store fault at work, not a request that would have been refused anyway.
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        Authorization authorization = Authorization.enforcing(store);
        String key = Authorization.mint("acme");
        authorization.provision("acme", Authorization.hash(key), "k", null);
        authorization.grant(key, "*", Authorization.REPOSITORY_READ);
        Map<String, Object> attributes = new HashMap<>();

        var result = manager(authorization).authorize(() -> null, new RequestAuthorizationContext(request(key, attributes)));

        assertThat(result.isGranted()).as("a clean store with the right granted allows the request").isTrue();
        assertThat(attributes.get("jenesis.repository.decision")).isEqualTo(Authorization.Decision.ALLOWED);
    }
}
