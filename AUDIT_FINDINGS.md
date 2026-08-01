# STATUS: CLEARED (free repo)

All 44 confirmed findings from the module-by-module audit are resolved: the 3 production
defects (posture wildcard fail-open, OCI manifest digest-verify, ui panel manifest) + the
vacuous security test were fixed in Round A, and the 39 test-gaps were closed with focused,
non-tautological tests (integrated commit b126283, build green).

Residual (1, documented — needs a production change + a free-core re-pin, so deferred):
- store/azure keyless-presign degrade catches IllegalStateException but azure-storage-blob
  12.35.0 throws NullPointerException, so the catch is a no-op. Low blast radius (the provider
  installs a shared-key presigner in production). See the follow-up note below.

---

# Module Audit — Confirmed Findings (free repo, Round A)

Iterative module-by-module audit: test gaps / test quality / defects / security, each finding adversarially verified by an independent agent. Production defects + the vacuous security test are fixed this pass; the remaining minor test-gaps are a durable coverage backlog (test-only — no re-pin needed).

Total confirmed: 44 · fixed this pass: 4 · remaining backlog: 40

## major (13)

- `gc/store` — `MarkSweepGarbageCollector.java:112` _(test-gap)_ — plan()'s incomplete-mark branch and the whole lastCompletedGeneration() method it calls are never executed by any test.
  - fix: Add a test that runs plan() against a store whose mark pass is incomplete (e.g. one mark segment left CLAIMED/expired) and assert plan judges against the last completed generation's shards, plus a clock-jumped-generation case.
- `walk/store` — `StoreArtifactWalk.java:190` _(test-gap)_ — The corrupt/unparseable-manifest self-heal path (clock-based generation rebase) and parseManifest/parseSegment null-on-corruption handling are entirely untested.
  - fix: Add a test that corrupts the manifest (and a segment) with junk bytes, re-walks, and asserts the pass restarts at a fresh (clock-based) generation and still visits every key exactly once.
- `format/spi` — `PrivateHosts.java:57` _(test-gap)_ — The hand-rolled CGNAT (100.64/10) and IPv6 unique-local (fc00::/7) branches of the SSRF classifier PrivateHosts.isPrivate have no test.
  - fix: Add a PrivateHosts unit test asserting isPrivate is true for 100.64.0.1 / 100.127.255.254 and an fc00::/fd00:: address, and false for 100.63.x / 100.128.x and a public v6 address.
- **[FIXED]** `format/oci` — `OciFormat.java:574` _(test-gap)_ — The entire OCI Distribution bearer-token auth flow (fetch()/download() 401 challenge handling and token() realm exchange) is untested.
  - fix: Add a proxy test whose Fetcher first returns 401 with a Bearer challenge, serves a token at the realm URL, then returns 200 on the retried request; assert the blob/manifest is served.
- `server` — `RepositoryProperties.java:115` _(test-gap)_ — The hand-rolled storage-quota parser quotaBytes() (decimal + K/M/G/T[/B/IB] suffixes, and an unknown-unit throw) has no test exercising any suffix, decimal, empty, or the throw branch.
  - fix: Add a unit-test table for quotaBytes(): '', plain count, each of K/M/G/T and their *B/*IB spellings, a decimal (e.g. 1.5G), and an unknown-unit that asserts the IllegalArgumentException.
- `proxy` — `HttpFetcher.java:101` _(test-gap)_ — The 64 MiB MAX_FETCH_BODY cap on the buffered fetch() path has no test anywhere in the tree.
  - fix: Add a proxy test that stubs an upstream returning >64 MiB and asserts fetch() throws IOException containing 'fetch limit', plus a body exactly at the cap that succeeds.
- `proxy` — `HttpFetcher.java:106` _(test-gap)_ — The fail-closed timeout->Optional.empty() behavior (fetch/download/head) is untested despite a dedicated constructor seam.
  - fix: Add a test with a short requestTimeout against a fixture that sleeps past it, asserting fetch()/download()/head() each return Optional.empty().
- **[FIXED]** `posture/spi` — `SecurityPosture.java:63` _(security)_ — The wildcard-console-admin advisory only fires when jenesis.ui.admins equals exactly "*", so it fails open when "*" appears as one element of the comma-separated admins list.
  - fix: Parse the value the same way Principals does: split on ',', trim, and raise the advisory when the resulting set contains "*", not only when the whole value is "*".
- **[FIXED]** `posture/spi` — `SecurityPostureTest.java:177` _(test-quality)_ — noAdvisoryTextEverRepeatsAConfiguredValue is vacuous: it asserts advisory text doesNotContain("SECRETVALUE") but never puts "SECRETVALUE" (or any secret) into the config.
  - fix: Feed a key whose value is a sentinel secret (e.g. a key the seeder reads set to "SECRETVALUE") and assert the rendered text excludes that exact sentinel; better, exercise a seed that actually reads a secret-bearing value.
- `oidc` — `OidcExchange.java:37` _(test-gap)_ — The multi-trust iteration is never exercised: every OidcExchangeTest case configures exactly one trust per tenant, so the 'a token that fails/does-not-match trust A but matches trust B' fall-through (the reason exchange() is a loop) is untested.
  - fix: Add a test that provisions two trusts for one tenant and presents a token that only matches the second (by issuer/audience/subject), asserting it is exchanged against that second trust.
- **[FIXED]** `ui` — `build.jenesis.repository.ui.Panel:3` _(defect)_ — The classpath ServiceLoader manifest lists only 3 of the 5 Panel providers declared in module-info (LogPanel and ConsistencyPanel are missing), so the two declarations disagree.
  - fix: Add build.jenesis.repository.ui.LogPanel and build.jenesis.repository.ui.ConsistencyPanel to the META-INF/services file so it matches module-info's provides list.
- `ui` — `LogPanel.java:28` _(test-gap)_ — LogPanel has no test at all - id(), title() and render() are never exercised, unlike its sibling ConsistencyPanel which has ConsistencyPanelTest.
  - fix: Add a LogPanelTest mirroring ConsistencyPanelTest: assert id/title and that render(null) contains /api/logs, Jenesis-Repository-Key, repository:read and the jlogsEsc escaping hook.
- `ui` — `ConsoleAdvice.java:38` _(test-gap)_ — ConsoleAdvice is entirely untested; its anonymousRights() auth-gated branch, readOnly() and postureCount() model attributes have no test.
  - fix: Add a ConsoleAdvice unit test over a mock Environment covering auth=false -> "", auth=true with anonymous-rights set -> trimmed value, readOnly true/false, and postureCount.

## minor (31)

- `store/filesystem` — `FilesystemArtifactStore.java:191` _(test-gap)_ — page()'s in-flight .upload*.tmp filter is untested, though it is a distinct code path from list()'s filter (only list() is covered).
  - fix: In PageTest, write a `.upload*.tmp` file into the paged directory alongside real entries and assert page() never emits it, mirroring the list() temp-hiding test.
- `store/spi` — `ArtifactStore.java:31` _(test-gap)_ — The security traversal backstop ArtifactStore.segment() has three untested rejection branches: the single-dot `.`, the backslash separator `\`, and `null`.
  - fix: Add cases asserting store.scope(".") , store.scope("a\\b") and a null segment each throw IllegalArgumentException, alongside the existing `..`/`a/b`/empty cases.
- `store/s3` — `S3ArtifactStore.java:77` _(test-gap)_ — S3ArtifactStore.presign() has no test - neither the presigned-URL-minting path nor the presigner==null empty fallback.
  - fix: Add a MinIO-backed test that builds the store with an S3Presigner and asserts presign(key,ttl) returns a URI whose path carries the scope prefix, plus a case asserting the presigner-less store returns Optional.empty().
- `store/gcs` — `GcsArtifactStore.java:68` _(test-gap)_ — GcsArtifactStore.presign() has no test - the SigV4 presigned-GET path and the presigner==null fallback are both unexercised.
  - fix: Drive presign() against the MinIO-backed provider store and assert the returned URI includes the scoped key prefix; add an empty-fallback case for a store built without a presigner.
- `store/azure` — `AzureArtifactStore.java:55` _(test-gap)_ — AzureArtifactStore.presign() is untested, including the IllegalStateException->Optional.empty() degradation for non-shared-key credentials (lines 62-67).
  - fix: Add an Azurite test asserting presign() returns a SAS URI over the scoped key, and a unit test with a token/AAD-credential client asserting presign() returns Optional.empty() instead of throwing.
- `store/s3` — `S3ArtifactStoreTest.java:195` _(test-quality)_ — a_ranged_read_seeks_to_the_window_over_a_real_range_get asserts only window correctness, which passes even if the store never issued a Range GET - the test's own comment admits it.
  - fix: Assert the range is really pushed to the wire - e.g. read a window from a large object and bound the bytes actually transferred, or intercept/inspect the issued request's Range header - rather than only checking the resulting window bytes.
- `store/s3` — `S3ArtifactStore.java:175` _(test-gap)_ — The open() SPI method has no test in any object-store backend (s3, gcs, azure).
  - fix: Add a round-trip test that writes a blob then reads it back through store.open(key) (try-with-resources), asserting the streamed bytes and that a missing key surfaces an IOException, in each object-store suite.
- `store/gcs` — `GcsArtifactStore.java:292` _(test-gap)_ — The readVersioned() fail-fast when the endpoint returns no x-goog-generation header (a generic S3 endpoint mistakenly pointed at the gcs backend) is untested.
  - fix: Point the in-process stub at a mode that omits x-goog-generation and assert readVersioned() throws an IOException naming the missing header rather than fabricating a token.
- `walk/spi` — `WalkSegment.java:27` _(test-gap)_ — Public method WalkSegment.claimable(Instant) has no test and is not called anywhere in production.
  - fix: Add a WalkSegment unit test covering PENDING, live-CLAIMED (expiry after now), expired-CLAIMED, null-expiry, and DONE across the now boundary.
- `walk/spi` — `WalkConsumer.java:45` _(test-gap)_ — WalkConsumer.discovered() (ServiceLoader enumeration + Features.enabled filter) is never invoked by any test.
  - fix: Register a WalkConsumer service in the test module and assert discovered() includes it when enabled and omits it when jenesis.repository.<name>=false.
- **[FIXED]** `format/oci` — `OciFormat.java:316` _(defect)_ — A manifest PUT by digest reference never verifies the body actually hashes to the referenced digest (unlike the blob push path, which does).
  - fix: In the manifest PUT branch, when reference starts with `sha256:`, compare hex(reference) to ingested.hex() and respond 400 MANIFEST_INVALID on mismatch, mirroring the blob store() digest check.
- `format/spi` — `FetcherProvider.java:33` _(test-gap)_ — FetcherProvider.resolve - the seam deciding whether any upstream proxying/import is possible - has no test.
  - fix: Add a FetcherProvider test with ServiceLoader-discovered stub providers asserting: explicit selection wins, a create()-empty provider is skipped, and NONE is returned when none is enabled.
- `format/raw` — `RawImporter.java:25` _(test-gap)_ — RawImporter.importTarget - the screen identity the import edge gates a raw asset against - is never asserted by any test.
  - fix: Add a RawImporterTest case asserting importTarget("dir/x") and importTarget("/dir/x") both yield a descriptor with ecosystem "raw" and path "/raw/dir/x".
- `importer/maven` — `MavenSource.java:149` _(test-gap)_ — The directory-listing walk's recursion depth cap (throw 'Directory tree exceeds depth 64') has no test, although the analogous Artifactory folder-crawl cap is explicitly tested.
  - fix: Add a MavenSourceTest that serves a self-referential/deep autoindex chain and asserts forEach throws IOException containing 'depth', matching the Artifactory depth-cap test.
- `importer/jenesis` — `JenesisSourceProvider.java:30` _(test-gap)_ — The provider's non-trivial API-key selection `key = password != null ? password : username` (password preferred, username as fallback) is untested at the provider level.
  - fix: Add provider tests: a request with only a username applies that as the key, and a request with both applies the password (not the username), asserting the resulting walk sends the expected Jenesis-Repository-Key header.
- `importer/maven` — `MavenXml.java:68` _(test-gap)_ — MavenXml has no direct test; its packaging() default-to-'jar' (no <packaging> element) and null-on-unparseable branches, and versions() empty-on-unparseable/missing-versioning branch, are all unexercised.
  - fix: Add a MavenXmlTest (or refresh-path cases) covering: packaging with no element => 'jar'; unparseable pom => null; broken/empty metadata => empty versions list.
- `importer/maven` — `RepositoryIndex.java:49` _(test-gap)_ — The index reader's corruption guards for an implausible field count (line 49), an implausible field length (line 57), and a truncated field value (line 62 EOFException) are untested; only the per-field decompression-bomb cap is covered.
  - fix: Add tests writing a raw index with (a) a field count above MAX_FIELD_COUNT, (b) a declared field length above MAX_FIELD_LENGTH, and (c) a value shorter than its length prefix, each asserting an IOException/EOFException from next().
- `server-spi` — `Authorization.java:801` _(test-gap)_ — recordUsed()'s exhausted-retry forfeit (returns false after USE_COUNT_RETRIES so the usage tracker keeps and re-applies the delta) is never exercised, and its boolean result is never asserted.
  - fix: Add a store double whose writeVersioned always conflicts on the metadata key and assert recordUsed(...) returns false and left no partial write; also assert the true-on-success return in the existing happy-path test.
- `server-spi` — `Authorization.java:719` _(test-gap)_ — The inRange() partial-byte mask branch (remainingBits != 0) — and the IPv6 masked-CIDR path — is never exercised: every CIDR in the tests is /8 or an exact address, so bits%8 is always 0.
  - fix: Add addressAllowed/inRange cases with non-byte-aligned prefixes (e.g. /26, /20) and an IPv6 masked CIDR, asserting an in-range address is admitted and a just-outside one is refused.
- `server` — `RateLimitFilter.java:96` _(test-gap)_ — The filter's per-tenant ceiling override (authorization.rateLimit(tenant) replacing the deployment default, then cached per bucket) has no test; RateLimitFilter has no unit test and the E2E only exercises the deployment default.
  - fix: Unit-test RateLimitFilter (or add an E2E) with a well-formed tenant key whose Authorization.rateLimit(tenant) differs from the default and assert its bucket meters at the override; also cover the /actuator skip and the rejected()/rejectedByTenant() counters.
- `proxy` — `HttpFetcher.java:164` _(test-gap)_ — The MAX_REDIRECTS=5 loop bound (redirect < MAX_REDIRECTS guard) has no test.
  - fix: Add a fixture whose handler always 302s to itself and assert the fetch terminates returning the redirect response after MAX_REDIRECTS hops rather than looping.
- `proxy` — `HttpFetcherProvider.java:31` _(test-gap)_ — HttpFetcherProvider has no test; the hand-rolled missTtl duration parser and the '0 disables the negative cache' branch are entirely uncovered.
  - fix: Add a provider test table covering PT90S, 90s, 5m, 500ms, blank(default), a bad suffix(throws), and 0 (create returns a fetcher that does not negative-cache).
- `proxy` — `NegativeCachingFetcher.java:74` _(test-gap)_ — The head() 404-caching branch of NegativeCachingFetcher is untested; only fetch() and download() are exercised.
  - fix: Add a test that a HEAD 404 is remembered and a second head() is answered from memory without reaching the delegate, mirroring the existing fetch/download cases.
- `proxy` — `RevalidatingFetcher.java:70` _(test-gap)_ — The drop-and-subtract-bytes path (a previously cached entry superseded by a no-validator or oversized 200) is untested - the exact accounting leak the code comment warns about.
  - fix: Cache an index with a validator, then return a 200 with no validator for the same URL and assert revalidation.bytes returns to 0 and the entry is dropped.
- `usage` — `BatchingKeyUsageTracker.java:274` _(test-gap)_ — flush()'s recordUsed()==false branch (leave `flushed` unadvanced so the delta is re-attempted) is untested.
  - fix: Add a test with an Authorization stub whose recordUsed returns false once then true, asserting the same delta is re-flushed on the subsequent drain rather than dropped.
- `posture/spi` — `Severity.java:20` _(test-gap)_ — Severity.worst(other) is a public method with no test and no production caller, whereas its sibling Health.worst is thoroughly tested.
  - fix: Add a test mirroring SignalDescriptorTest.health_severity_collapses_to_the_worst for Severity (INFO/WARN/CRITICAL), or drop the unused method.
- `posture/spi` — `Configuration.java:55` _(test-gap)_ — Configuration.of(UnaryOperator) - the production factory that wraps the Spring Environment - is never exercised; all tests build config via ofMap.
  - fix: Add a test that builds Configuration.of(map::get) and asserts value()/optional()/flag()/number() read through the lookup, plus that of(null) throws.
- `ui` — `LoginController.java:35` _(test-gap)_ — LoginController's OAuth-provider listing branch and its already-authenticated redirect are untested.
  - fix: Add a slice/unit test with a stub ClientRegistrationRepository asserting registrations are listed and oauthConfigured=true, plus one asserting an authenticated principal redirects to /console.
- `bundle` — `Console.java:18` _(test-gap)_ — The Console launcher (the console node of the all-in-one image) and allinone-console.properties have no test; only AllInOne is booted.
  - fix: Add a bundle E2E that boots the console node (via Application.start or a Console-equivalent) against a fresh store and asserts /login serves and /console denies anonymously, mirroring the server-node test.
- `ui` — `OAuth2PrincipalService.java:28` _(test-gap)_ — The provider-qualified id construction in OAuth2PrincipalService and OidcPrincipalService - the exact string that decides ADMIN - is untested.
  - fix: Add tests over a stubbed OAuth2User/OidcUser asserting the id passed to Principals.authorities is 'github/<login>' and 'oidc/<sub>' respectively.
- `ui` — `ConsistencyPanelTest.java:33` _(test-quality)_ — ConsistencyPanelTest asserts escaping only by substring-checking the identifier 'jconEsc', which cannot verify the escaping actually works.
  - fix: Cover the escaping behaviourally (e.g. a JS-engine or browser test that renders a malicious node id/reason and asserts it is neutralised), or downgrade the assertion's claim to match what it checks.

---

## Follow-up surfaced during backlog test-writing (Round A)

- **[minor / production]** `store/azure` — `AzureArtifactStore.java:62-67` — the keyless-client presign degradation catches `IllegalStateException`, but under the pinned `azure-storage-blob 12.35.0` a no-shared-key `generateSas` throws `NullPointerException`, so the catch never fires and `presign()` propagates instead of returning `Optional.empty()`. Low blast radius (the provider installs a shared-key presigner in production), but the degrade is a no-op. Fix: widen the catch to the actual thrown type (or `RuntimeException`) — a production change, so deferred out of the test-only backlog pass (would need a re-pin).
