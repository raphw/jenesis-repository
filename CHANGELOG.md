# Changelog

## 0.5.0

Screening unified at the ingress edges (EPIC 26). Publication screening is no longer embedded in the
formats; it now happens once, at the write edges, and the formats are pure layout writers.

- **Screening is the ingress edges' monopoly.** Every write is screened at its edge - the deploy edge
  (`ScreenedDispatch`), the batch explode edge, the import walk (`RepositoryImport.run`), and OCI's
  manifest choke point (`OciManifests.ingest`) - each running the discovered `PublishInterceptor`
  chain once over the neutral `ArtifactDescriptor` and, only on `ACCEPT`, laying the stored blob out.
- **`Publication.publish` removed.** The old embedded screen-and-link is gone (no deprecation shim).
  The surviving publish choreography is `screen` -> (on `ACCEPT`) format layout via `storeBlob`/`link` ->
  `published`; `screen` still diverts a `QUARANTINE` verdict to the quarantine view and links nothing on
  `REJECT`.
- **New after-commit and describe seams.** `Publication.published()`/`deleted()` fire the
  `PublicationObserver`s for an artifact an edge laid out itself (a blobs-namespace deploy now observed
  too), `PublishInterceptor.screened()` and `RepositoryImporter.describe()` let a format/importer hand
  the edge its layout descriptor so an observer keys on the neutral ecosystem/coordinate/version.
- **OCI manifest choke point.** A `docker push`, a pull-through fetch and an import walk all route the
  manifest through one `Publication.screen`, mapping the verdict onto OCI's native `withheld/<hex>`
  marker; layer blobs stay served raw (the manifest that names them is the screened unit).
- **Structural guard.** A source-scanning guard asserts no format/importer screens outside the edges,
  with `OciManifests` the single documented, allowlisted exception.

## 0.3.0

Security-defaults hardening and a real-Nexus import fix.

- **Secure default: per-credential authorization on by default.** A fresh deployment now
  enforces authorization; anonymous is the explicit opt-out (`jenesis.repository.auth=false`
  / `JENESIS_REPOSITORY_AUTH=false`), with a loud startup warning when disabled.
- **Nexus import fix (real Nexus 3.71+).** The Nexus import walk normalises the absolute
  (leading-slash) asset paths the H2/PostgreSQL datastore reports, so a migration off a
  current `sonatype/nexus3` imports its components instead of silently dropping them all.
- **SSRF guard on the import endpoint** — private/loopback/link-local/CGNAT/ULA hosts are
  refused by default (`block-private-import-hosts=false` to opt out).
- **Session cookie `Secure` defaults `true`** (`JENESIS_UI_SECURE_COOKIE=false` for local HTTP).
- **Empty `admins` denies admin** rather than granting it to every sign-in (list `*` to opt out).
- **Object-store endpoint overrides must be `https`** unless explicitly opted in
  (`JENESIS_AWS_ALLOW_INSECURE_ENDPOINT` / `JENESIS_GCS_ALLOW_INSECURE_ENDPOINT`).
- **Negative cache is bounded** — a flood of distinct un-expired 404s can no longer grow the
  map past its cap.
- **Non-`https` proxy upstreams warn loudly** at boot.

## 0.2.0

Format-gate publish routing on top of the `Fetcher.head` + `PublishInterceptor.Content.store`
SPI additions the enterprise edition pins.

## 0.1.0

Initial published free core.
