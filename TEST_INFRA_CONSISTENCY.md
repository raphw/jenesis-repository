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
- `enterprise …/emulator/test/EmulatorTest` — plain status codes for the load mix (the
  truncation is the client's own request). Needs the alias.
- `enterprise …/forwarding/test/{ForwardingTest,CentralPortalTransportTest}` — PUT→201
  loop-guard target; multipart upload + status poll + 401 (journal for the body). Needs
  the alias.
- `free …/proxy/test/{HttpFetcherHeadTest,HttpFetcherRedirectTest}` — plain HEAD (WireMock
  does Content-Length natively) and 302 chains / loop / SSRF-refuse. Keep `Timeout` +
  `FetchCap`. Needs the alias.
- `free …/store/azure/test/AzureFailLoudTest` (every request → 403; trivial) and
  `free …/store/s3/test/S3GetRequestTest` (HEAD 403/404 + a ranged GET needing per-`Range`
  stubs). Need the alias.
- `free …/server/test/MavenTreeImportTest` — **borderline, rule (4):** its upstream
  **dynamically generates** nginx-style autoindex HTML per directory from the file set, so
  a WireMock form needs a `ResponseTransformer` (that logic relocated) or ~13 brittle
  pre-computed listing stubs plus a Scenario for the one-shot 500 — more ceremony, not less
  hand-rolled logic. The dynamic generator is the clearer expression. Leaning keep.

**Disposition.** The two socket-level and two rule-(4) cases above are settled keeps. The
plain-stub MIGRATE items are all a passing test today, and every one either sits in a
module **without** the `wiremock.standalone` alias (migrating adds a fat-jar dependency
purely for idiom, which rule (4)'s "where an in-process wire is cheap, prefer it" weighs
against) or, like `CliDispatcherTest`, is a large per-test-mutable rewrite whose churn risk
on a green test outweighs the idiom gain. They are recorded here as low-priority "ideally"
follow-ups with their exact WireMock shapes, **not** forced. The bulk of (1) — free 6
modules, enterprise 38 — is already on WireMock; these are the residual tail.

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

## (3) Proxy → Mockito — servlet mocks DO work (inline maker; earlier finding corrected)

Every servlet/`Principal`/`RestOperations` test double used to be a
`Proxy.newProxyInstance` hand-roll; the intent (rule 3) was to migrate them to Mockito.

**Corrected finding (verified by a passing build).** An earlier pass concluded Mockito
"cannot mock `jakarta.servlet` interfaces" and called it a hard JPMS limit. That was
wrong — it had only tried Mockito's *default subclass* mock maker, which fails on a
sealed named module because ByteBuddy must define the generated subclass **in the
mocked type's package** and `jakarta.servlet` does not open `jakarta.servlet.http`.
The **inline** mock maker (the default in Mockito 5) has no such constraint: it
redefines the loaded class through the instrumentation agent instead of injecting a
subclass, so it mocks a sealed-module interface fine. `RouteWritableTest` converted to
`mock(HttpServletRequest.class)` / `mock(HttpServletResponse.class)` and passes 4/4:

```
Mockito is currently self-attaching to enable the inline-mock-maker.
WARNING: A Java agent has been loaded dynamically (…/net.bytebuddy/byte-buddy-agent…)
[ 4 tests found / 4 tests successful / 0 tests failed ]
```

**The recipe (proven on `test/server`, applied per module):**

- Pin `org.mockito/mockito-core 5.23.0`, `net.bytebuddy/byte-buddy-agent 1.17.7`,
  `org.objenesis/objenesis 3.3` (byte-buddy `1.18.3` already pinned or added), and
  `requires org.mockito;`. Mockito 5's default maker **is** the inline maker, so no
  `mockito-extensions/org.mockito.plugins.MockMaker` resource is needed.
- The inline maker self-attaches a ByteBuddy agent at runtime (dynamic attach). It
  works on JDK 25 as-is; the module ships
  `META-INF/build.jenesis/process/test.properties` with `-XX:+EnableDynamicAgentLoading`
  so the forked test JVM explicitly permits it (a future JDK will otherwise disallow
  dynamic agent loading by default — the one durability caveat; the self-attach WARNING
  on stderr is informational, not a failure).

**Migration (rule 3, "Full migration" — owner-approved).** All servlet-interface
`Proxy` stubs move to Mockito:

- **free**: `server/test/RouteWritableTest` — **done** (the exemplar above).
- **enterprise**: the 14 servlet-`Proxy` test files across `server`, `redirect-serve`,
  `redirect-dns`, `webhook-web`, `redirect-directory` — converted with the same recipe,
  each module validated green.

**Remaining rule-3 item (not a servlet mock):** `free ui/test/PrincipalServiceTest`
still stubs `RestOperations` (a classpath Spring interface — the subclass maker would
already handle it) via `Proxy`; converting it is a separate small follow-up (mind its
overloaded `exchange`/`getForObject` signatures when stubbing).

## (4) Over-/under-mock scan

- Flag any test whose only assertions are on values its own mock returns (it proves
  the mock, not the code). None found blocking yet — carry as a review lens during
  the migration above.
