# Changelog

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
