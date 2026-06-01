---
id: S-190
title: CI build wallclock — investigate + tighten alpenflight build to ≤5 min p95
epic: E-01
status: todo
origin: ops-followup
kind: ci-performance
acceptance:
  - The `alpenflight build` job in `.github/workflows/ci.yml` finishes in **≤5 min wall p95** on `ubuntu-22.04` for a no-op PR push (cache-warm) and **≤8 min p95** for a full-cache-miss push.
  - Step-level wallclock breakdown is captured (one-time investigation deliverable in the PR description) so future regressions are diagnosable. At minimum: setup-java, setup-gradle, `./gradlew build`, setup-node + pnpm-store-restore, `pnpm install`, `pnpm run generate-api` drift check, `pnpm lint + format + test + build:prod`.
  - Root cause for the current ~8–10 min wall is named in the PR description (not guessed): JVM cold start (`--no-daemon` defeats reuse), serial server-then-web execution in one job, missing Gradle build cache hits, Spring context fan-out, etc.
  - At least one of the levers below is applied; pick whichever the breakdown identifies as load-bearing:
    1. Split the `alpenflight build` job into parallel `server-build` + `web-build` jobs so the wall is `max(server, web)` instead of `server + web`.
    2. Drop `--no-daemon` on the Gradle invocation; let `gradle/actions/setup-gradle@v4` manage the daemon + remote build cache.
    3. Consolidate `@SpringBootTest` context shapes per the implement-skill Step 5.5 menu so the test phase amortises ≤ 3-4 cached contexts.
    4. Add `org.gradle.parallel=true` + tune `maxParallelForks` (verify runner memory: GitHub `ubuntu-22.04` is 7 GiB / 2 vCPU; each fork needs ~3 GiB for Spring + Testcontainers).
    5. Cache `~/.gradle/caches` keyed on lockfile / wrapper version (the `setup-gradle` action does most of this; verify cache hits in run logs).
  - Required-check graph (`required` job in ci.yml) still passes when the split job lands; no regression in `alpenflight e2e` schedule.
  - Surface the new wallclock in the next PR's `gh run view` to confirm the budget holds end-to-end.
estimate: M
adr_refs: []
refined: false
---

## Context

S-141's mark-done CI run [#26691854749](https://github.com/elekktrisch/fls/actions/runs/26691854749) had `alpenflight build` still in `in_progress` ~7 min after the commit pushed — the operator flagged it: the build should be ≤5 min wall.

Observed shape (`.github/workflows/ci.yml:50-146`):

1. Single `alpenflight build` job runs server + web **sequentially** in one runner:
   - `Set up JDK 25` → `Set up Gradle` → `./gradlew build --no-daemon` (server)
   - then `Set up Node 22` → `Set up pnpm` → restore pnpm-store cache → `pnpm install --frozen-lockfile` → `pnpm run generate-api` drift check → `pnpm lint + format + test + build:prod` (web)
2. `--no-daemon` on the Gradle invocation forces a JVM cold start every push (defeats setup-gradle's daemon-warm path).
3. The `setup-gradle@v4` action does cache `~/.gradle/caches` but the daemon JVM still cold-starts.

Likely wall-time culprits (to confirm in the investigation):
- Gradle cold-start + Spring context boot under Testcontainers SharedPostgresContainer (≥ 3-4 distinct contexts = 30-60 s of compile-once-boot-many overhead).
- Serial server-then-web execution — server-build wall is dead time for the web steps and vice versa.
- pnpm install on a cache hit is fast (~10 s); on a miss it's a minute+. `pnpm-lock.yaml` change triggers full re-download.
- `pnpm build:prod` (Angular production build) is the second-largest single step (~60-90 s typical).

## Investigation deliverables

Captured in the PR description, not in committed docs (rots fast):

- Per-step wall from `gh run view <run-id> --json jobs` on three runs: cache-hit no-op, cache-hit small-diff, cache-miss (e.g. lockfile change).
- Top 3 levers ranked by `wall-saved / implementation-cost`.

## Cross-story contracts

None. Touches only `.github/workflows/ci.yml`. Does not change story-implementation contracts.

## Non-goals

- Switching off `ubuntu-22.04` to bigger runners (cost-side decision, separate ADR).
- Splitting `alpenflight e2e` (separate workflow, separate budget).
- Replacing Testcontainers with the dev-profile remote-Postgres path system-wide — that's an environment decision, not a CI-perf one (see S-141 OpenApiSnapshotWriterUsingRemotePostgres for the per-snapshot exception).
