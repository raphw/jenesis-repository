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

- **Enterprise**: Testcontainers is a real dependency (reached via the
  `@jenesis.alias org.testcontainers …` descriptor-less alias). **DONE this session:**
  the shared `docker`-CLI `Minio.java` helper (duplicated across `server`, `index`,
  `search`, `reclamation`, `recovery`, its only caller of `Docker.java`) is re-backed
  on a Testcontainers `GenericContainer` and the per-module `Docker.java` deleted; each
  module-info gains the alias + docker-java/jna/duct-tape/testcontainers pins +
  `requires org.testcontainers;`. Validated by **real container runs** (docker daemon
  started in-session): `index`/`recovery`/`reclamation` MinIO tests and `search`'s
  roundtrip pass green against a Testcontainers MinIO. `SeleniumContainer` is already a
  Testcontainers class — keep.
  - **Latent bug surfaced (tracked):** running `SearchIndexMinioTest`'s cutover-race
    test for the first time (it was Docker-gated → skipped in CI) revealed that the
    search-index generation CAS is not concurrency-safe under the S3 store's SSE ETag
    semantics (enterprise always encrypts; MinIO's SSE ETag ≠ content MD5). The test is
    `@Disabled` with a pointer here until `SearchIndexTask`'s manifest CAS is hardened
    for SSE ETags. This is orthogonal to the container mechanism.
  - **Remaining docker-CLI helpers to migrate** (separate images, own validation): the
    Keycloak helper behind `server/…/KeycloakTokenExchangeE2ETest`, `Compose.java`, and
    the format `*RealClient`/`*QualityInspector` docker helpers under `gateway`/format
    modules. Same recipe: re-back on `GenericContainer`, one image at a time, each
    proven by a real run.
- **Free**: the earlier claim that Testcontainers "cannot be a module dependency in
  this build" (repeated in the old `Docker.java` Javadocs) is **stale** — the
  `@jenesis.alias org.testcontainers …` mechanism works in free exactly as in
  enterprise. **DONE this session:** the free store integration tests migrated off
  their per-module `docker`-CLI `Docker.java` to a Testcontainers `GenericContainer` —
  `store/s3` + `store/gcs` (MinIO) and `store/azure` (Azurite), both the
  `*ArtifactStoreTest` and `*ArtifactStoreProviderTest` in each — and the three
  `Docker.java` copies deleted. Validated by **real container runs** (MinIO + Azurite
  start and the store round-trips / conditional-CAS pass green). The free build stays
  green with no daemon (the tests skip, as CI runs today).
  - **Remaining free helpers to migrate** (own images + validation): the OCI-registry
    tests (`OciDockerTest`/`OciImporterTest`/`OciProxyTest`), the Nexus/Artifactory
    import tests (`NexusImportTest`/`NexusSourceTest`/`ArtifactoryOssImportTest` — heavy
    images, slow boot), and `SeleniumContainer` (below).

- **`SeleniumContainer` (both repos)** is a docker-CLI helper too, not yet a
  Testcontainers class. It runs the node under **host networking** so the in-container
  browser reaches the ephemeral-port console the test boots on the host loopback — a
  Testcontainers rewrite must replace that with `Testcontainers.exposeHostPorts(port)` +
  the `host.testcontainers.internal` gateway address in the console browser tests, so it
  is a real change (not a drop-in), tracked with the rest.

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
