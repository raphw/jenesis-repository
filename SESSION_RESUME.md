# Session Resume — Enterprise/Free-core Security-Defaults Audit Fan-out

**Last updated:** 2026-07-20 (session 2). This file is the durable resume point: a fresh
session with no prior context can pick the work up from here. It is docs-only (no CI cost)
and lives on the designated branch `claude/enterprise-main-green-audit-hh7dpv` in BOTH repos.

## Mission

Autonomous security-defaults audit + backlog clearing across two repos:
- `raphw/jenesis-repository` (free-core, PUBLIC) — separate CI pool, fast.
- `raphw/jenesis-enterprise` (enterprise, PRIVATE) — CI budget is the constraint.

Plan-of-record: `TASKS.md` (EPICs 0–18) + `AUDIT_FOLLOWUPS.md` (evidence log), both on the
designated branch. Contract: `PRINCIPLES.md` (§1 stream-never-buffer, §4 persist-through-store /
converge-idempotent, §5 never-serve-incomplete-view / open-ended-kinds-survive, §9 fail-fast /
make-errors-visible, §10 reads-render / writes-refresh). NO backwards-compatibility shims.

## Strategy (user-directed)

Grind tickets in TASKS order; verify LOCALLY (warm cache); **batch each epic's tasks into ONE
branch = ONE PR = one CI build**; accept CI may stop (a locally-verified PR just waits for
budget). Collect parallel per-task agents' file-disjoint commits onto one `claude/epicN-batch`
branch, verify module-scoped, then ONE PR. "Take as many issues as you can while you can run."

## USER DECISIONS on the held / design-first tickets (2026-07-20) — ALL GREENLIT

1. **T11.1 — Compound `(instant, path)` resume cursor for `PublishedIndexTask`** → **IMPLEMENT.**
   Same-millisecond publishes split across incremental passes are skipped until the P7D rebase.
   Needs a real compound cursor (a naive `isBefore` swap duplicates chained records).
2. **T10b.6 — Publish-time advisory persistence** → **IMPLEMENT, seam (B):** the commit hook
   re-queries the *named* feeds (a `FeedCache` hit right after assess) rather than reshaping
   `ComplianceGate.Assessment`. Closes the between-sweeps window (a coordinate published after the
   last sweep renders clean until the next sweep).
3. **EPIC 10b — Principle §10 UX sweep (T10b.1–T10b.5)** → **FULL CONSISTENCY, do all five.**
   Roll the "reads render, writes refresh" contract (shipped exemplar = vuln panel T10b.0) onto
   every derived/externally-sourced view: T10b.1 findings screen (staleness line), T10b.2
   Scorecard/OpenSSF health panels, T10b.3 dependents/reachability views (last-rebuild instant +
   write-gated on-demand rebuild), T10b.4 console modules/settings live-probe screens (bind to
   stored state + explicit re-probe), T10b.5 free-core `[FREE]` proxy upstream health/index views.

## Progress tally (EPIC 2–12)

**MERGED (17):** all EPIC 2 (T2.1–2.6), EPIC 3 (T3.1/3.2), EPIC 5 (T5.1–5.3), EPIC 6 (T6.1–6.5),
EPIC 7 (T7.1 free+emulator+pypi-proxy), EPIC 9 (T9.1/9.2/9.3), T4.1(#52), T4.2/4.3/4.4, T8.1/8.2(#51),
T10.1/10.2/10.4/10.8, T10.9(free #16), T11.3/11.5, T11b.1(already on main #5a6f351), T11b.2, T12.1/12.2.

**IN FLIGHT (open PRs / active branches at last checkpoint):**
- Ent PR **#53** `claude/epic8-batch` — T8.3/8.4/8.5/8.6/8.7 (5 commits; ArtifactSbom T8.5/T9.3
  semantic merge resolved). Local `+test+dependency`/`+dependents` green. CI hit the flaky
  `ConsoleBrowserTest` (T16E.1, unrelated) — re-triggered.
- `claude/epic10-batch` pre-staged with T10.7 (commit on `wt-epic10`). Agents still to collect:
  T10.3 (`wt-t103`), T10.5-rem attribution rollup (`wt-t105`), T10.6 per-format index (`wt-t106`).
- `claude/epic11-batch` pre-staged with T11.2 (commit on `wt-epic11`). Agent to collect: T11.4/11.6/11.7 (`wt-t114`).

**NOT YET WORKED (EPIC 2–12), queued:**
- T11.8 (grab-bag MINOR correctness — wide-file, run isolated), T11b.3 (swallowed-error sweep — wide-file).
- T12.3 (Central park-healthy-slow), T12.4 (Central resume content-hash), T12.5 (Outbox.unpark stale twin) — one agent, shared files.
- EPIC 5-rem (Instances/Eviction console-audit remnant).

**NEWLY GREENLIT (from decisions above), queue after EPIC 2–12 core:**
- T11.1 (compound resume cursor), T10b.6 (seam B), T10b.1–T10b.5 (full §10 sweep) → `claude/epic10b-batch` (+ T11.1 with EPIC 11 or its own).

## Resume procedure

**Build env (JDK 25) — use SEPARATE export lines (a one-liner mis-expands `$JAVA_HOME` → silently picks JDK 21):**
```
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export JAVA_HOME=/tmp/claude-0/-home-user/604c1314-ac98-5ce0-9e97-774c20b0550f/scratchpad/jdk25
export PATH=$JAVA_HOME/bin:$PATH
```
Build MODULE-SCOPED: `java build/jenesis/Project.java +test+<module>` (e.g. `+test+gateway`,
`+test+server`, `+test+dependency`). **Do NOT append `build`** (triggers full reactor → unrelated
env-test failures). `-D` props go BEFORE the `Project.java` path. Local sandbox has a WARM cache.

**Batch-collect pattern:** create `claude/epicN-batch` off latest `origin/main` ONCE; cherry-pick
each agent's file-disjoint commits onto it; resolve any semantic conflict by hand (e.g. two tasks
editing one file); verify module-scoped; ONE PR per epic. Free-core tasks get their own PRs
(separate CI pool).

**CI triage:**
- `repo.jenesis.build` 502 ("failure happened outside a test JVM", module-resolution IOException)
  = transient infra → re-trigger with an empty commit. Skip a cycle if it 502s twice.
- `ConsoleBrowserTest` (test/browser) dialog-timeouts = flaky under CI load (T16E.1), unrelated to
  most changes → re-trigger. Passes ~19–21/23. Recurs on any ui/server-touching batch.
- GitHub "artifact storage quota has been hit" post-step = COSMETIC (build result already decided).
- Ignore these two sandbox-only test failures locally (they pass on real CI): 
  `VulnCheckKnownExploitedSourceTest.an_unreachable_catalogue_is_fail_soft` (test-injected IOException),
  `LiveAdvisoryFeedTest.the_github_advisory_database_reports_log4shell` (GitHub-403 in sandbox).

**Merge policy:** merge free-core greens freely (fast pool); merge enterprise greens as budget
allows. If enterprise CI stops building (budget exhausted): leave locally-verified PRs OPEN, note
it, keep free-core + local builds going. Squash-merge.

**Docs:** once a batch merges, tick `AUDIT_FOLLOWUPS.md` + `TASKS.md` on the designated branch (docs-only, trivial CI lane).

## OUT OF SCOPE (reported to user; do NOT start without an explicit "yes")

EPIC 13–18 (~35 tickets): webhook retry surface, staging promotion all-or-nothing, modularity
extractions, CISA/EPSS provider correctness, regression-test coverage (incl. T16E.1 browser-suite
stabilization), large-module refactors, SPDX SBOM parser. Categorically feature/refactor/test work,
not the security/correctness drive.

## Completion criterion

When all non-held EPIC 2–12 + the newly-greenlit decisions (T11.1, T10b.6, T10b.1–5) are PR'd
(merged where CI allows) and docs reconciled: report completion and STOP the driver re-arm loop.
