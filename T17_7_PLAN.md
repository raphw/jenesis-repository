# T17.7 — free-core `Authorization` seam (investigation + plan)

**Ticket (EPIC 17, modularity splits):** turn `build.jenesis.repository.server.Authorization`
into a clean *seam* — an interface/SPI the free core exposes — so an enterprise or alternate
authorization model can plug in without the free core hard-coding one, mirroring the sibling free
SPIs (`ArtifactStore` / `ArtifactStoreProvider`, `PublishInterceptor`, `TokenExchangeProvider`,
`KeyUsageTrackerProvider`, `RateLimiterProvider`).

**Outcome of this ticket: PLAN ONLY (no code change).** The extraction is large, cross-module,
and touches the security-critical path. Per the standing directive (no half-measures, clean
cutovers) and the auth-behaviour caution, a botched auth-seam refactor is worse than none. The
plan below is the deliverable; implementation should be scheduled as its own reviewed change.

---

## 1. Current shape

`source/server/build/jenesis/repository/server/Authorization.java` — a single `public final`
class, ~830 lines, that is the *entire* credential model. It bundles several concerns that a clean
seam would separate:

- **Authorization decision** (the true seam an alternate model wants to own):
  `authorize(key, scope, required)`, `authorize(key, scope, path, required)` → `Decision`
  (`ALLOWED`/`UNAUTHORIZED`/`FORBIDDEN`), `addressAllowed(key, clientAddress)`, `enforced()`.
- **Credential-store management** (console/admin surface, ~25 methods): `grant`, `grantAll`,
  `setGrant`, `removeGrant`, `provision`, `revoke`, `revokeLeaked`, `rotate`, `setExpiry`,
  `setAllowedAddresses`, `credential`, `credentials`, `policy`, `setPolicy`, `quota`, `setQuota`,
  `rateLimit`, `setRateLimit`, `trusts`, `setTrust`, `removeTrust`, `roles`, `setRole`,
  `removeRole`, `mintExpiry`, `recordUsed`.
- **Stateless key crypto utilities** (`static`): `mint`, `hash`, `wellFormed`, `tenantOf`,
  `checksum` (private), `clientAddress`.
- **Construction** (`static` factories over a `private` constructor + 3 `final` fields):
  `anonymous()` (open: every request `ALLOWED`, `store == null`), `enforcing(store)`,
  `withDefaultLifetime`, `withMaxLifetime`, `defaultLifetime()`, `maxLifetime()`.
- **Nested types**: `Decision`, `Credential`, `Policy`, `Trust`, `Rotated` (records/enum).
- **Constants**: `CACHE_READ/WRITE`, `REPOSITORY_READ/WRITE`, `MANAGE_READ/WRITE`.

The whole thing is stored over the `ArtifactStore` SPI under `auth/<tenant>/<hash>/{grants,metadata}`
plus per-tenant `policy`/`quota`/`ratelimit`/`oidc`/`roles` objects. `store == null` is the open
("anonymous") mode; a non-null store is enforcing.

### How it is constructed and injected
One Spring `@Bean` in `RepositoryAutoConfiguration` (lines 71–85), already
`@ConditionalOnMissingBean`:

```java
@Bean @ConditionalOnMissingBean
public Authorization authorization(RepositoryProperties properties, ArtifactStore store) {
    ... // warns loudly when auth is disabled
    return properties.isAuth() ? Authorization.enforcing(store) : Authorization.anonymous();
}
```

That single bean is injected everywhere else. **The seam intent already exists** (the
`@ConditionalOnMissingBean` lets a downstream module supply its own `Authorization` bean) — but it
is *unusable* today because `Authorization` is `final` with a `private` constructor, so no
alternate implementation can be built. **That is the actual seam gap.**

## 2. Blast radius (real references only)

`rg -l Authorization` also matches the HTTP `Authorization` *header* in
`proxy/HttpFetcher`, `importer/{index,nexus,maven,artifactory}`, `format/{oci,spi}` — those are
**not** our class and are out of scope. The class `build.jenesis.repository.server.Authorization`
is referenced from **~17 files across 3 JPMS modules**:

- **`source/server`** (12 files): `RepositoryAuthorizationManager` (21 refs — the decision hot
  path), `RepositorySecurityAutoConfiguration` (12, wiring), `RepositoryAutoConfiguration` (3,
  the bean factory), `RepositoryAuthorizationEntryPoint` (6), `RateLimitFilter` (5),
  `KeyUsageTrackerProvider` (4), `TokenExchangeProvider` (3), `RepositoryController` (2, the
  admin/console surface — calls most management methods), `RepositoryProperties`,
  `RepositoryApplication`, `KeyUsageTracker`, `KeyAuthenticationFilter` (1 each).
- **`source/oidc`** (`requires build.jenesis.repository.server`): `OidcExchange` calls
  `Authorization.Trust`, `authorization.trusts()`, static `Authorization.mint`/`hash`,
  `authorization.provision()`, `authorization.setGrant()`; `OidcExchangeProvider` receives it.
- **`source/usage`** (`requires build.jenesis.repository.server`): `BatchingKeyUsageTracker`
  holds an `Authorization` field and calls `authorization.recordUsed(...)`;
  `BatchingKeyUsageTrackerProvider` receives it.
- **Tests** (`test/server`): `AuthorizationTest` (the behaviour contract), `RepositoryAuthE2ETest`,
  `RepositorySpringE2ETest`, `OidcExchangeTest`, `KeyUsageTrackerTest`, `FeatureTogglesTest`.

Because oidc and usage call *management* methods (`trusts`, `provision`, `setGrant`, `recordUsed`)
and the console calls the full management surface, a **non-breaking** interface would have to
re-declare essentially the *entire* public surface — decision + ~25 management methods + the
`static` helpers + 5 nested types + 6 constants. That is not a narrow seam; it is re-publishing the
whole class as an interface.

## 3. Idiomatic seam shape in this codebase (target pattern)

`ArtifactStoreProvider` is the reference: an interface in a small `*.spi` module, a named-provider
with a `static resolve(name, config)` that does `ServiceLoader.load(...)` and falls back to a
bundled default (`filesystem`), `uses`/`provides` in the module graph, default impl in its own
module (`store/filesystem`). `TokenExchangeProvider`, `KeyUsageTrackerProvider`,
`RateLimiterProvider` are the same shape but *`uses`-only inside the server module* (default is "off"
/ 501 when no provider is on the path).

## 4. Proposed seam

Two viable designs; recommend **B** for the free repo, with **A** as the enterprise-facing follow-up.

### Design A — full SPI (mirrors `ArtifactStoreProvider`)
1. Split `Authorization` into an **interface** (the exported contract) + a package-private default
   impl `StoreAuthorization` (the current class body, behaviour byte-for-byte identical).
2. Keep the `static` helpers (`mint`/`hash`/`wellFormed`/`tenantOf`/`clientAddress`) and the
   `static` factories (`anonymous`/`enforcing`) **on the interface** so existing
   `Authorization.mint(...)` / `Authorization.enforcing(store)` call sites keep compiling. Nested
   types + constants stay on the interface unchanged.
3. Add `AuthorizationProvider { String name(); Authorization create(ArtifactStore store,
   UnaryOperator<String> config); static Authorization resolve(...) }` with `ServiceLoader`
   discovery and a bundled default provider — the store-backed model — as the fallback.
4. The `@Bean` factory calls `AuthorizationProvider.resolve(...)` instead of the static factories.
5. Module-graph churn: new `uses build.jenesis.repository.server.AuthorizationProvider` + `provides`
   in `source/server` (or a new `source/server/authz-spi` module if the interface must live outside
   the server package to avoid the enterprise depending on the whole server module — likely needed,
   since oidc/usage already `requires server` only for this type).

**Cost:** highest. Interface surface is ~35 members; a new SPI module + provider; re-export/`provides`
across 3 modules; every `new`/factory path rerouted.

### Design B — extract interface, keep the existing `@ConditionalOnMissingBean` seam (recommended first step)
1. Rename the current class to `StoreAuthorization` (default impl, unchanged behaviour) and introduce
   `public interface Authorization` in the same package carrying the full public surface + the
   `static` helpers/factories/nested types/constants (so **zero** call-site edits outside the two
   files below).
2. `Authorization.enforcing(store)` / `anonymous()` become `static` factory methods on the interface
   returning `new StoreAuthorization(...)`.
3. Leave the `@Bean` exactly as is (it already returns via the factories and is already
   `@ConditionalOnMissingBean`). An enterprise module now simply defines its own `Authorization`
   `@Bean` (or an `AuthorizationProvider` in a later step) — the seam that was intended but blocked by
   `final` is now open.

**Cost:** moderate but still substantial — an ~830-line type is split into interface + impl, the
interface must declare ~35 members to stay non-breaking, and all 3 modules + 6 test files recompile
against the new interface. No new external deps. No ServiceLoader yet (add in Design A follow-up).

## 5. Why plan-only (recommendation)

- **Cross-module, security-critical.** The change spans `server` + `oidc` + `usage` and sits on the
  request-authorization hot path (`RepositoryAuthorizationManager.authorize`) and the
  credential-management surface. A regression flips a request from denied→allowed or vice-versa.
- **The "seam" is nearly the whole class.** Because consumers use the decision *and* management *and*
  static surface across module boundaries, a non-breaking interface re-declares almost everything —
  the modularity win is real but the mechanical surface is large, not a tidy narrow SPI.
- **JPMS `provides`/`uses`/`exports` churn** across three modules (likely a new `authz-spi` module to
  keep oidc/usage from depending on the full server module) is exactly the "module-graph change" the
  ticket flags as the do-not-half-implement trigger.
- **Behaviour must be byte-for-byte preserved** (open mode = `store == null` ⇒ `ALLOWED`; enforcing =
  grant lookup with exact-then-`*` scope fallback, expiry-before-grants, IP allowlist). This wants a
  dedicated change with `AuthorizationTest` + the auth E2E tests green *unchanged*, reviewed on its
  own, not folded into a batch.

## 6. Recommended sequencing (when scheduled)

1. **Design B** first (interface + `StoreAuthorization` default, no ServiceLoader) — smallest
   behaviour-preserving step; `AuthorizationTest`, `RepositoryAuthE2ETest`, `RepositorySpringE2ETest`,
   `OidcExchangeTest`, `KeyUsageTrackerTest`, `FeatureTogglesTest` must pass **unchanged**.
2. **Design A** as a follow-up (add `AuthorizationProvider` + `ServiceLoader` + optional `authz-spi`
   module) once the enterprise plug-in point is actually needed — matching `ArtifactStoreProvider`.
3. Consider splitting the interface along the natural seam (a narrow `AuthorizationDecider` for the
   request hot path vs. an `Credentials`/management interface) so an alternate model can own the
   *decision* without re-implementing the console management surface — the cleaner long-term seam.

**Verification bar for the implementing change:** build `+source+server +source+oidc +source+usage`
and `+test+server`; GREEN with the auth tests passing with **no assertion changes** (any
allowed↔denied flip is a red flag and blocks the change).
