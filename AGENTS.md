# AGENTS.md

The free core: the repository / build-cache server modules consumed by downstream editions. Design rationale lives in [`DESIGN.md`](DESIGN.md).

## Build & test

- `java build/jenesis/Project.java` resolves, compiles, and tests every module. **Requires JDK 25** (the sources use module-import declarations and unnamed variables).
- Fast iteration — build one module's subgraph with a `+` selector: `+source+<path>` or `+test+<path>` (e.g. `+test+store+s3`; a nested `store/s3` is written `+store+s3`, and `/<step>` drills into a step). `+` selectors are *lenient* — a wrong one silently matches nothing, so confirm your module shows an `[EXECUTED]` line.
- `-Djenesis.*` system properties go **before** the `Project.java` path; bare selectors go **after** it.
- `java build/jenesis/Project.java help` documents every selector and `-D` flag (`pin` regenerates the `@jenesis.pin` version/checksum lines; `export` produces the deliverable repository).

## Local gotchas (a red here is often the environment, not a regression)

- Container-backed tests (MinIO / Nexus, driven through the `docker` CLI) **self-skip without Docker**; the strict CI lane (`-Djenesis.project.properties=ci`) makes them *fail* instead.
- A few tests need a **UTF-8 locale** (non-ASCII path cases) or a **`GITHUB_TOKEN`** (the live advisory-feed smoke). Set `LANG=C.UTF-8` for the former.
