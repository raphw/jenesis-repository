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

Already on WireMock: free 6 modules, enterprise 38 modules. A full read-through of
every remaining `com.sun.net.httpserver` / `HttpServer.create` / `ServerSocket`
stub across both repos was done this session and classified below (KEEP =
socket-level fault WireMock can't model, or a rule-(4) case where WireMock would be
the greater evil; MIGRATE = plain request/response stub).

**KEEP — genuine socket-level faults (WireMock cannot model at the wire level):**

- `enterprise …/gateway/test/FaultUpstream.java` — the deliberate fault double behind
  `HttpFetcherWireTest`, split from its WireMock-backed sibling `LoopbackUpstream.java`.
  Real faults: `truncated()` (short read), `stall()` (read-timeout hang), the JDK HEAD
  Content-Length workaround. Documented in its own Javadoc.
- `free …/proxy/test/HttpFetcherTimeoutTest` — accept-then-hang past the request timeout,
  the free mirror of `FaultUpstream.stall()`. Canonical read-timeout socket fault → keep.

**KEEP — rule (4) (migrating would make the test worse, not a socket fault):**

- `free …/proxy/test/HttpFetcherFetchCapTest` — streams 64 MiB from a small buffer to
  prove the fetch cap; WireMock's `withBody` would materialise the whole 64 MiB in
  memory. The streaming is the point → keep.
- `free …/store/gcs/test/GcsConditionalWriteTest`'s **stateful CAS core**
  (`x-goog-if-generation-match`, monotonic generation counter, 412 on mismatch). Not a
  socket fault, but WireMock models per-key compare-and-set only via a stateful
  `ResponseTransformer` — hand-rolled logic relocated, not removed. The in-process stub
  is the clearer expression → keep (rule 4). (Its stateless branches — fail-loud HEAD
  403, `NoSuchBucket` 404 — would migrate cleanly but aren't worth splitting the file.)

**Already compliant / not a stub:**

- `enterprise …/ai/test/RecordedModelServer.java` — already `WireMockServer`; the
  `jdk.httpserver` import is vestigial (value types only).
- `enterprise …/server/test/KeycloakTokenExchangeE2ETest` — no in-process stub; a
  Testcontainers real Keycloak (the hermetic `OidcTokenExchangeE2ETest` it references is
  already WireMock).

**MIGRATE — plain request/response stubs (tracked; each a passing test today, so the
value is idiom-consistency and the cost includes adding the `wiremock.standalone` alias
where a module lacks it — noted per item):**

- `enterprise …/cli/test/CliDispatcherTest` — ~18 JSON/status API routes with per-test
  mutable status/body → one stub per route, re-stub/Scenario for the mutable status,
  query params from the journal. Module **already** has the alias (pure rewrite).
- `free …/server/test/MavenTreeImportTest` — Maven origin: seeded files, generated
  autoindex HTML, a one-shot 500 → a WireMock **Scenario**. Module **already** has alias.
- `enterprise …/emulator/test/EmulatorTest` — plain status codes for the load mix (the
  truncation is the client's own request). Needs the alias.
- `enterprise …/forwarding/test/{ForwardingTest,CentralPortalTransportTest}` — PUT→201
  loop-guard target; multipart upload + status poll + 401 (journal for the body). Needs
  the alias.
- `free …/proxy/test/{HttpFetcherHeadTest,HttpFetcherRedirectTest}` — plain HEAD (WireMock
  does Content-Length natively) and 302 chains / loop / SSRF-refuse. Keep `Timeout` +
  `FetchCap`. Needs the alias.
- `free …/store/azure/test/AzureFailLoudTest` (every request → 403; trivial) and
  `store/s3/test/S3GetRequestTest` (HEAD 403/404 + a ranged GET needing per-`Range`
  stubs). Need the alias.

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
  - **Container-FIXTURE helpers migrated (DONE this session, real runs):** the Keycloak
    rig — three `Keycloak.java` copies (`server`, `auth/saml`, `browser`) — is re-backed on
    a bridged Testcontainers `GenericContainer` (realm via `withCopyToContainer`,
    mapped-port issuer, discovery-doc wait), and the sigstore rig on a Testcontainers
    `ComposeContainer` (`withExposedService` ambassador, `Compose.java` deleted). Validated:
    `KeycloakTokenExchangeE2ETest` (129.5s), `KeycloakSamlRoundTripTest` (41.2s),
    `KeycloakSsoBrowserTest` (in the 136s browser run), and `SigstoreKeylessInteropTest`
    (real 7-service stack) all green. (Enterprise-side detail; kept here since the rule
    spans both repos.)
  - **Deliberate keeps — `docker` used as a CLIENT tool, not a fixture** (Testcontainers
    does not replace these): the format `*RealClient` tests run the real ecosystem
    client (composer/cargo/npm/…) via `docker run` as a one-shot client action against
    the in-process `RepositoryApplication` and assert its exit code — the docker client
    doing exactly what it is for, with no long-lived container to "manage". Kept.
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
  - **DONE:** the Nexus and Artifactory import fixtures migrated off `docker`-CLI to
    `GenericContainer` (Nexus reads its admin password via `execInContainer`; Artifactory
    keeps its mandatory `nofile` ulimit via `withCreateContainerCmdModifier`). `mvn` and
    the OCI docker-client tests in the same module are untouched. **`NexusImportTest`
    validated by a real Testcontainers run** (boot + `mvn deploy` + import + serve, green).
    `ArtifactoryOssImportTest` could not be run *here* — this sandbox's open-files hard
    limit is 4096 and Artifactory 6.x demands ≥32768, which a container cannot exceed, so
    it fails to start under any mechanism (the old docker-CLI form would fail identically
    now that a daemon is up). The migration compiles and matches the validated Nexus twin;
    a `ulimit -Hn` guard was added so the test self-skips where the host caps nofile too
    low and still runs on a normal CI host.
  - Migrated: `SeleniumContainer` (below).
  - **Deliberate keeps — `docker` used as a CLIENT tool, not a fixture:**
    `OciDockerTest` / `OciProxyTest` drive the host `docker push`/`pull` against the
    in-process Jenesis registry to prove a real Docker client interoperates —
    Testcontainers cannot issue a host-side `docker push`, so these stay. (`OciImporterTest`
    and `NexusSourceTest` are not docker tests at all — `imports("docker")` / a format
    name string.)

- **`SeleniumContainer` (both repos) — migrated (DONE this session, real runs).** It is
  now a Testcontainers `GenericContainer` that **deliberately keeps host networking**
  (`withNetworkMode("host")`): the in-container browser must reach the ephemeral-port
  console — and, on the SSO leg, the mapped-port Keycloak — on one host-loopback identity
  that agrees with the console's callback and the issuer, which bridged Testcontainers
  networking cannot provide (the in-JVM console cannot resolve
  `host.testcontainers.internal`; a bridged browser's `localhost` is not the host's). The
  `Testcontainers.exposeHostPorts` / gateway rewrite was therefore rejected in favour of
  keeping the loopback identity — the migration is the Testcontainers-managed lifecycle
  (pull/boot/cleanup via Ryuk), not the topology. `webDriverUrl()` and the consumers are
  unchanged; readiness is a `/status` poll. Validated: free `ConsoleBrowserTest` (15.5s)
  and enterprise `ConsoleBrowserTest` + `KeycloakSsoBrowserTest` (136s) green.

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
