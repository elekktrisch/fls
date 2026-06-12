---
name: do-task
description: Implement ONE task of a journey in a clean context — TDD the slice, commit directly to the journey integration branch, tick the checklist, stop. The worker /do-ship dispatches per task; also runnable standalone (manual or /loop) for a guaranteed fresh-context task run. Trigger: /do-task J-NNN [T-NN | next].
---

# do-task — one task, clean context

Do exactly one task of a journey and stop. You start with a clean context on
purpose: a distinct task gets full, undegraded attention, then the context is
thrown away. `/do-ship` spawns you per task; you also run standalone (drive a
journey by hand or via `/loop /do-task J-NNN`).

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).
Schema is structural; business rules on aggregates. Per directive 1: code that
ships the slice beats prose.

**Search posture.** Default to MCP servers over raw grep: the IntelliJ MCP
(`search_in_files_by_regex`, `search_in_files_by_text`, `find_files_by_glob`,
`search_symbol`, `get_symbol_info`) for code search/navigation and the
**codebase-memory-mcp** for prior-art recall. Fall back to `Grep`/`Glob` only
when no MCP server is connected.

## Scope discipline

- **One task.** The task named in the arg, or the first unticked `T-NN` in the
  journey's `## Tasks` checklist if `next`. Do not drift into adjacent tasks —
  another fresh worker takes those.
- **Clean context is the feature.** Load only what THIS task needs. Don't read
  the whole journey history; read the task line, the spec contract, and the code
  the task touches.
- **You don't run the journey gate.** The full legacy→migrate→real chain + video
  is `/do-ship`'s journey-level job. You prove your task locally (fast inner
  loop) and leave the heavy chain to the gate.

## Procedure

### 1 — Load just enough

Read: the journey file's frontmatter + `## Spec must assert` + your task's
`T-NN` line; the `adr_refs`; the specific code/spec paths the task touches.
**Refresh the graph first:** prior tasks committed code to this branch, so run
`detect_changes`; if it reports drift, `index_repository` (incremental) before you
recall — otherwise codebase-memory-mcp returns stale prior art. Then recall prior
art via codebase-memory-mcp. If the task is parity-sensitive and no
behavior oracle is in hand, dispatch `legacy-oracle` for just this task's
behavior. For libraries (Angular/Spring/Playwright/NgRx), fetch current docs via
Context7. Confirm the working tree is on `integration/J-NNN`.

**Overflow tripwire (now, before writing any test or commit).** Check the task
against the same caps `/do-ship` dispatched on: **one seam** (one aggregate / one
component / one resource's endpoints / one migration / one spec edit), **≤8 files
touched, ≤5 new, ≤3 tests at one layer**, one logical change. If loading reveals it
exceeds any (e.g. the spec scope names 3 entities, or 'the service' is really 5
endpoints), **stop before writing code** — append an `OVERFLOW: <which cap, real
count — suggest T-NNa=X, T-NNb=Y…>` note under the task line in the journey file and
return `status: overflow`. Fire this in your first 2–3 reads, before any commit — a
half-built oversize task is the expensive case the manager re-plans around
(`/do-ship` § 3a). **Standalone** (no manager — manual or `/loop`): escalate to the
operator instead of returning to a manager; `/do-task next` **skips** a task that
already carries an `OVERFLOW` note (never silently re-dispatch the same oversize id).

### 2 — Red first (for this task's slice)

Write the failing test at the task's layer — a unit/integration test for logic,
or (for the spec-stub / spec-thicken tasks) the Playwright assertions. Watch it
fail for the *right* reason (expected-vs-actual, not a NullPointer). For a
spec-stub task, "red" = the structure compiles and asserts thinly against the
not-yet-built screen.

### 3 — Implement the slice

Build only this task. Typical task shapes:
- **Migration:** Flyway `V<n+1>__*.sql`, structural only (ADR 0022 directive 2 —
  no CHECK/trigger/generated for business rules; those go on aggregates).
- **Backend slice:** entity → repository → service → controller + unit tests.
  `@PreAuthorize` + `@TenantId` per the oracle's tenancy rule. **JPA-first
  (ADR 0027), blocking:** never add `JdbcTemplate`/`createNativeQuery` — the
  register is a shrinking list for structurally-pre-tenant seams only; a
  complex read gets a domain-maintained read-model, not native SQL. Tests
  seed via production code (reflection only for non-settable attributes).
- **Frontend slice:** Signal Store → component → route + logic unit tests,
  consuming the regenerated client (per `alpenflight/web/CLAUDE.md`).
- **Proof-chain contribution:** this entity's legacy seed + per-entity migration
  mapper (lean on `e2e-driver` if the wiring is gnarly).
- **Spec stub / thicken:** author or fill the journey's Playwright spec. Tag any
  e2e case that tests *logic / an error path* (not UI↔backend↔DB wiring) as a
  helper — `@helper` + `covered-by: <IntegrationTest>` — so `/do-retro` can file
  a pruning story once the cheaper test exists. Never tag the wiring/happy-path spec.

Iterate to local green against the fast inner loop (mock-auth + Testcontainers,
or `page.route` mocks for FE-only). Honor the ≥5-min wallclock budget — surface
a slow loop rather than re-running it five times. **ANY backend slice must run the
full pure-JVM arch-guard suite — not just the touched test class** — because these
guards fire only on the whole-module build, so a targeted IT run passes while the
gate's `./gradlew build` goes red. Run all that apply: `ApplicationModulesTest` (Spring Modulith boundary — a new
cross-package import/call from `me`→`persons.application` etc. needs the target module
`OPEN` or a `@NamedInterface`), `ControllerAuditCoverageTest` (**every mutating
controller method** must reach `AuditTrail.record` or be `@AuditedBy` — a new `PATCH`/
`POST`/`PUT` endpoint that doesn't emit an audit event fails this), `NativeSqlRegisterTest`
(a new native-SQL call site on a tenant-scoped table needs a `native-sql-register.md`
entry), `AuditRedactionCoverageTest` (a new audited entity type needs its redaction
policy). When unsure which apply, run all four — they're cheap. Do NOT block uncommitted
on a long background gradle run — run verification foreground-bounded, then commit.

### 4 — Commit + tick

Commit **directly to `integration/J-NNN`**, per work-package (subject `#N: <task
summary>` / `J-NNN T-NN: …`). Don't push past red; don't `--no-verify` /
force-push. Tick `T-NN` in the journey's `## Tasks` checklist (one commit may
include the tick). **Push ownership (the manager owns the push when one is driving):**
- **Manager-driven (dispatched by `/do-ship`):** commit, then **report your SHA and RETURN
  immediately** — do **not** push, do **not** wait on / poll in-flight CI, do **not** spawn a
  background CI-waiter. The manager pushes at its cadence (it alone sees in-flight runs + the
  task sequence). A worker that hangs on CI or pushes mid-sequence creates the exact overhead
  this rule removes.
- **Standalone (manual / `/loop`, no manager):** commit, then `git push` as the last step —
  your commit is stranded otherwise. Don't push past a red local build.

Either way: don't `--no-verify` / force-push; don't push past red.

**Format + lint the touched files before you commit** — run the project formatter in
**write** mode then verify, over the **full glob** you changed (e.g. `prettier --write`
then `--check "src/**/*.{ts,html,css,json}" "e2e/**/*.{ts,json}"`, the module's `lint`),
not just the one or two files you eyeballed. `--check` alone reports but doesn't fix. A
format-only miss fails CI a whole round later — the most wasteful red there is. Cheap locally, expensive at the gate.

**Proof-gallery DoD.** If the task TOUCHED the proof gallery (the gallery generators, its
CI/deploy steps, or screenshot/video sidecars), run the autonomous link check before marking
done: `GALLERY_LINKS_ONLY=1 pnpm exec playwright test --config=e2e/playwright.config.ts --project=proof-gallery-links` (browserless; "are all gallery links live?").

**Local-first verification DoD.** Before reporting `done`, run `pnpm preflight` (or the matching `--scope`: `preflight:web` for FE-only tasks, `preflight:no-e2e` when chromium is absent) — the comprehensive local CI-equivalent — NOT a focused `--tests`/single-spec subset; comprehensive local green is the CI round-trip fix. **Backend: run `./gradlew check`, NOT `test`** — the build gate's red-makers (`cpdRatchet`, `pmdMain`, the arch-guards, `OpenApiSnapshotIT`) live in `check`, not `test`; a `test`-green commit reds CI on `cpdRatchet` (J-6 T-04/05). And run it on **every module your change reaches, not just the obvious one** — a binding edit in `migration-bundle` reds an `ExportCommandSmokeTest` in `migration-tool` (J-6 T-11); when unsure, `check` from the repo root. **CI/workflow edits: validate by the REAL check, not `js-yaml`** — run `actionlint` or a real dispatch (it misses GitHub's expression-length limit + `success()`/`!cancelled()` step-skip semantics).

**Boyscout (uncommitted leftovers).** A small incidental fix or cleanup you made
in passing doesn't need its own commit/PR — leave it in the working tree and let it
**ride with the next regular change** on `integration/J-NNN`. Never craft a separate
chore commit just to isolate it. (Don't leave the task's own deliverable uncommitted
— this is for stray drive-by edits, not the work the task owns.)

### 5 — Stop + report

Return a lean summary (this is what the manager keeps): task id + status (done /
overflow / escalated / blocked), commit subjects, ACs touched, any `@mocked:` seam
declared, and escalations. **Then stop** — do not pick up the next task.

## Escalate, don't guess

Stop and escalate (to `/do-ship`'s manager, or the operator if standalone) when:
a parity assertion only passes by changing behavior; a previously-green test in
another slice breaks; a `depends_on` artifact is missing; ported legacy code has
an apparent bug (never silently fix); the AC is unmeetable as written. Consider a
one-shot read-only consult first (`legacy-oracle` for behavior; `e2e-driver` for
a flaky spec) — one consult per fork, no chaining.

## Quality bar

- Exactly one task. No drift, no gate, no journey-level PR decisions.
- One seam per task; if it spans several, emit `OVERFLOW:` before any commit, don't push through.
- Red-first at the task's layer; fail for the right reason.
- Commit directly to `integration/J-NNN`; never push past red.
- Schema structural; business rules on aggregates.
- No new JDBC/native SQL outside the register (ADR 0027) — gap-hunter blocks it.
- Default real; any mock declared with an `@mocked:` tag for the journey PR list.
- Code self-explanatory; cite by file:line, never SHAs in committed text.
- Leave a ticked checklist + a lean report. Don't prune the journey body (that's
  `/do-ship`'s finish step).

## When done

The one task is committed to `integration/J-NNN`, its checklist box ticked, and a
lean report returned. The next task is another fresh worker's job.
