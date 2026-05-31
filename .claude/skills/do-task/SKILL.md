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
Recall prior art via codebase-memory-mcp. If the task is parity-sensitive and no
behavior oracle is in hand, dispatch `legacy-oracle` for just this task's
behavior. For libraries (Angular/Spring/Playwright/NgRx), fetch current docs via
Context7. Confirm the working tree is on `integration/J-NNN`.

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
  `@PreAuthorize` + `@TenantId` per the oracle's tenancy rule.
- **Frontend slice:** Signal Store → component → route + logic unit tests,
  consuming the regenerated client (per `alpenflight/web/CLAUDE.md`).
- **Proof-chain contribution:** this entity's legacy seed + per-entity migration
  mapper (lean on `e2e-driver` if the wiring is gnarly).
- **Spec stub / thicken:** author or fill the journey's Playwright spec. Tag any
  e2e case that tests *logic / an error path* (not UI↔backend↔DB wiring) as a
  helper — `@helper` + `covered-by: <IntegrationTest>` — so `/do-retro` can prune
  it once the cheaper test exists. Never tag the wiring / happy-path spec.

Iterate to local green against the fast inner loop (mock-auth + Testcontainers,
or `page.route` mocks for FE-only). Honor the ≥5-min wallclock budget — surface
a slow loop rather than re-running it five times.

### 4 — Commit + tick

Commit **directly to `integration/J-NNN`**, per work-package (subject `#N: <task
summary>` / `J-NNN T-NN: …`). Don't push past red; don't `--no-verify` /
force-push. Tick `T-NN` in the journey's `## Tasks` checklist (one commit may
include the tick). If you opened nothing, `/do-ship` handles the draft PR; if a
PR exists, push and let CI run.

### 5 — Stop + report

Return a lean summary (this is what the manager keeps): task id + status (done /
escalated / blocked), commit subjects, ACs touched, any `@mocked:` seam declared,
and escalations. **Then stop** — do not pick up the next task.

## Escalate, don't guess

Stop and escalate (to `/do-ship`'s manager, or the operator if standalone) when:
a parity assertion only passes by changing behavior; a previously-green test in
another slice breaks; a `depends_on` artifact is missing; ported legacy code has
an apparent bug (never silently fix); the AC is unmeetable as written. Consider a
one-shot read-only consult first (`legacy-oracle` for behavior; `e2e-driver` for
a flaky spec) — one consult per fork, no chaining.

## Quality bar

- Exactly one task. No drift, no gate, no journey-level PR decisions.
- Red-first at the task's layer; fail for the right reason.
- Commit directly to `integration/J-NNN`; never push past red.
- Schema structural; business rules on aggregates.
- Default real; any mock declared with an `@mocked:` tag for the journey PR list.
- Code self-explanatory; cite by file:line, never SHAs in committed text.
- Leave a ticked checklist + a lean report. Don't prune the journey body (that's
  `/do-ship`'s finish step).

## When done

The one task is committed to `integration/J-NNN`, its checklist box ticked, and a
lean report returned. The next task is another fresh worker's job.
