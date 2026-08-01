# Test-infrastructure consistency sweep

Standing rule for both `jenesis-repository` (free) and `jenesis-enterprise`:

1. **Wire formats are tested over WireMock**, not hand-rolled `jdk.httpserver` /
   `com.sun.net.httpserver` stubs or raw `ServerSocket`s.
2. **Containers come from Testcontainers**, not the `docker` CLI driven through a
   hand-rolled `Docker`/`Minio`/`Keycloak`/`Compose` helper.
3. **Mocks are Mockito**, not `java.lang.reflect.Proxy.newProxyInstance` hand-rolls.
4. **Mocks are not over-used**: where a real object or an in-process wire
   (WireMock / Testcontainers) is cheap, prefer it; a test must not assert only on
   the behaviour of its own stub.

The bulk of (1) and (2) landed in T19.2 (task #95). This document is the running
ledger of what remains, what is a **deliberate** exception, and what must migrate.

## (1) HTTP mocking → WireMock

Already on WireMock: free 6 modules, enterprise 38 modules.

**Deliberate residuals (keep — WireMock cannot model these at the wire level):**

- `enterprise …/gateway/test/FaultUpstream.java` — a real loopback socket that
  models socket-level faults (half-close, connection reset, an `ETag`→`304`
  revalidation wire) the plain stub duties of which already moved to the
  WireMock-backed `LoopbackUpstream`. Documented in its own Javadoc.
- `enterprise …/ai/test/RecordedModelServer.java`, `emulator/test/EmulatorTest`,
  `forwarding/test/{ForwardingTest,CentralPortalTransportTest}` — **REVIEW**:
  confirm each is a socket-level need; if it is a plain request/response stub,
  migrate to WireMock.
- `enterprise …/cli/test/CliDispatcherTest` — **REVIEW**.

**Genuine stragglers to migrate (free):**

- `proxy/test/HttpFetcher{Fetch,Head,Redirect,Timeout}Test` — these test the real
  `HttpFetcher` against a `jdk.httpserver` stub. `HttpFetcherWireTest` already uses
  WireMock (`LoopbackUpstream`); fold the four remaining into it **unless** they
  need a socket fault WireMock cannot express (timeout/half-close may — REVIEW).
- `server/test/MavenTreeImportTest` — **REVIEW**.
- `store/{gcs,s3,azure}/test/*` fail-loud + conditional-write stubs (added this
  session + pre-existing) — these drive the **S3 / Azure SDK** against an
  in-process stub. The stateless fail-loud branch (HEAD→403) is a clean WireMock
  candidate; the **stateful** GCS `x-goog-if-generation-match` CAS stub is not a
  clean WireMock fit and is a case where the hand-rolled stub is the lesser evil —
  REVIEW against rule (4) before forcing WireMock.

## (2) Docker → Testcontainers

- **Enterprise**: Testcontainers is a real dependency (9 modules use it). The
  `docker`-CLI helpers (`Docker.java`, `Minio.java`, `Keycloak.java`, `Compose.java`
  under `server`, `index`, `reclamation`, `recovery`, `search`, `compliance/spi`,
  `auth/saml`, `browser`) are the inconsistency: migrate each to a Testcontainers
  `GenericContainer` / `MinIOContainer` / a Keycloak container. `SeleniumContainer`
  is already a Testcontainers class — keep.
- **Free**: Testcontainers **cannot** be a module dependency in this build (the
  `Docker.java` helper Javadoc states it; free has 0 Testcontainers modules). The
  free `docker`-CLI helper is therefore a **deliberate constraint, not a
  violation** — leave it until Testcontainers is pinnable in free core.

## (3) Proxy → Mockito — BLOCKED for servlet mocks (empirically verified)

Mockito is pinned (transitively) but **directly used by zero test modules**; every
servlet/`Principal`/`RestOperations` mock is a `Proxy.newProxyInstance` hand-roll.
The intent was to migrate these to Mockito.

**Finding (verified by build):** Mockito's default *subclass* mock maker **cannot
mock `jakarta.servlet` interfaces** in this strict-JPMS build. Converting
`RequestBodyLimitFilterTest` to `mock(HttpServletRequest.class)` (with
`requires org.mockito;` + `mockito-core 4.11.0` / `objenesis` / `byte-buddy-agent`
pins) compiles, but fails at runtime:

```
org.mockito.exceptions.base.MockitoException:
  Mockito cannot mock this class: interface jakarta.servlet.http.HttpServletRequest
Caused by: java.lang.IllegalArgumentException:
  jakarta.servlet.http.HttpServletRequest$MockitoModuleProbe$… must be defined in
  the same package as org.mockito.codegen.InjectionBase
```

ByteBuddy's subclass maker must define the generated mock **in the mocked type's
package** via `Lookup`, and `jakarta.servlet` is a sealed named module that does not
open `jakarta.servlet.http`. This is a hard JPMS limitation, not a config miss. So:

- The `Proxy.newProxyInstance` servlet stubs are a **deliberate, correct workaround**
  — retained. `Proxy` mocks any interface without package injection.
- The **only** Mockito path for servlet interfaces here is the *inline* mock maker
  (`mock-maker-inline`), which redefines via the instrumentation agent instead of
  package injection. That needs (a) a `mockito-extensions/org.mockito.plugins.MockMaker`
  resource wired into each test module's build output and (b) a self-attaching
  ByteBuddy agent, which JDK 25 gates behind `-XX:+EnableDynamicAgentLoading`. It is a
  scoped spike — **not** a mechanical migration — and must not be attempted piecemeal.

**Actionable now:** where a mock is *not* of a sealed-module interface (e.g. a
first-party interface, or `RestOperations` on the classpath), Mockito is viable;
`ui/test/PrincipalServiceTest`'s `RestOperations` stub is a candidate (mind its
overloads). All `HttpServletRequest`/`Response`/`FilterChain` stubs stay on `Proxy`
until the inline-maker spike lands. Net: **keep Proxy for servlet mocks** — this
already satisfies rule (4) (the stub is minimal and the filter's real logic is
exercised).

## (4) Over-/under-mock scan

- Flag any test whose only assertions are on values its own mock returns (it proves
  the mock, not the code). None found blocking yet — carry as a review lens during
  the migration above.
