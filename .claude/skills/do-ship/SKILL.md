---
name: do-ship
description: Drive ONE vertical journey to a green PR. Manager skill — creates the journey integration branch, decides the task list, runs each task in a FRESH-context worker (clean context per task, no dumb-zone), then runs the real legacy→migrate→Keycloak→Playwright gate + video. Stops at a green PR for the operator to merge. Trigger: /do-ship J-NNN.
---

# do-ship — journey manager

Take one carved journey (`J-NNN`) and drive it to a green PR. You are a
**manager**: you decide the task list, then run each task in its own fresh
worker context so no single conversation accumulates the whole journey and
drifts into the dumb-zone. You hold only lean summaries; the heavy work lives
in the workers. When the tasks are done, you run the proof-chain gate and open
the journey PR. The operator merges.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md)
first — both directives govern. Schema is structural; business rules on aggregates.

**Search posture.** Default to MCP servers over raw grep: the IntelliJ MCP
(`search_in_files_by_regex`, `search_in_files_by_text`, `find_files_by_glob`,
`search_symbol`, `get_symbol_info`) for code search/navigation and the
**codebase-memory-mcp** for prior-art recall. Fall back to `Grep`/`Glob` only
when no MCP server is connected.

## Shape

One journey at a time (no fleet — vertical diffs collide). A journey takes many
tasks to get working, so it lives on its **own integration branch**
`integration/J-NNN`; tasks commit **directly** onto it. Each task runs in a
**fresh-context worker** (`/do-task`) — clean context every time. You stay lean:
decide the list, dispatch workers, collect one-line results, gate, PR.

On-demand escalation tools (no standing panel): `legacy-oracle` (exact legacy
behavior), `e2e-driver` (proof chain / Playwright), `gap-hunter` (attack the
green at the gate).

## The done bar — a real, honest green

A journey is done only when its Playwright spec drives the **real UI** end to
end and passes — first on a clean seed, then on real legacy data migrated into
AlpenFlight (Postgres + Keycloak). The pass-video is the acceptance artifact.

**Red is the work-list, not a wall.** Never done while red; the green bar is
self-imposed and absolute. **A journey never merges red.**

## Procedure

### 1 — Resolve + branch

Resolve `J-NNN` from arg or a `integration/J-NNN` branch. Bail if the journey
file isn't `carved: true` ("run /do-plan J-NNN first") or not `status: todo`
(in_progress → resume; done → refuse). Every `depends_on` journey must be `done`
+ its PR `MERGED`. Create/checkout `integration/J-NNN` off the current
integration line. Flip `status: in_progress` + `started_at`; create a GitHub
issue (`J-NNN: <title>`) if `gh`+remote; initial commit `#N: start`.

### 2 — Decide the task list (stay lean)

Refresh the graph before recall: run `detect_changes` and, if drifted,
`index_repository` (incremental) — the integration line may have moved since the
last index. Read the journey spec + its `rolls_up` stories + the legacy screen(s)
it replaces. For parity-sensitive screens, dispatch `legacy-oracle` ONCE to get the
behavior oracle (its output is a worker input, not something you internalize in
detail). Then write an ordered `## Tasks` checklist into the journey file —
`T-NN` ids, one-line scope each, dependency order. Default decomposition:

1. **T-01 — spec stub.** Author the Playwright spec's structure + selectors +
   flow steps with thin assertions (commits the screen shape).
2. **Vertical work-packages.** Migration → backend slice → frontend slice, split
   into tasks a fresh worker can each finish cleanly (one entity, one endpoint
   cluster, one component). Keep each task small enough that a clean context can
   hold all it needs.
3. **Proof-chain contribution.** This entity's legacy seed + per-entity mapper.
4. **Final task — thicken spec** to full real assertions from the oracle.

This list is your only durable state — workers and re-runs read it.

### 3 — Manager loop (fresh worker per task)

Ensure the working tree is on `integration/J-NNN`. For each pending task **in
order**, spawn ONE fresh worker subagent (clean context — that's the whole
point) to execute `/do-task` for that task:

> Agent (general-purpose): "Execute the `/do-task` playbook
> (`.claude/skills/do-task/SKILL.md`) for task `T-NN` of journey `J-NNN` on
> branch `integration/J-NNN`. <one-line task scope>. Commit directly to the
> branch. Return only: status, commit subjects, ACs touched, escalations."

Run tasks **sequentially** (shared branch + working tree — parallel would
conflict). After each worker returns: tick `T-NN` in the checklist, keep its
one-line summary, discard the detail. If a worker **escalates** (parity/legacy-
bug/unmeetable-AC/contract conflict), stop the loop and surface to the operator
per § Escalation. Do not push past a red task.

Push at task boundaries; after the first locally-green backend task, open a
**draft PR** (`gh pr create --draft --base <integration-line> --head
integration/J-NNN`, body `Closes #N` + AC checklist). Watch CI in background;
a red CI run becomes the next task, not a blocked wait.

### 4 — Proof-chain gate

When every task is ticked, run the gate (delegate to `e2e-driver`): the full
chain — legacy seed → migrate → Keycloak → real Playwright — both fidelities
green, **video retained on pass**. When the journey has a legacy counterpart,
`e2e-driver` also captures a **legacy `flsweb` video** of the same journey on the
seeded data — a **parity-review aid for the operator, not a pass/fail** (the
AlpenFlight green stays the gate). Greenfield journeys ship the AlpenFlight video
alone. On the PR these run as **two parallel CI jobs** — `alpenflight-proof`
(required) + `parity-legacy-video` (non-blocking artifact), `e2e-driver` owns the
workflow. For **Journey-0** (`journey0: true`) the gate work is itself the tasks:
stand up the thinnest whole chain for this one screen.

**Mock governance.** Happy + key-error cases run fully real — no mocking. Any
mocked seam (edge/error only) carries an inline `@mocked: <seam> — <reason>` tag
+ a PR **"Mocked seams"** list; ask the operator for **one signoff** at the
gate. Spawn `gap-hunter` ×2-3 against `git diff <base>...HEAD` + the spec + the
Mocked-seams list. Undeclared mocks, stubs, un-wired layers, tenancy leaks →
**chain is red**. Red gate cases become **new tasks** (append to the checklist);
return to step 3. Honor the ≥5-min wallclock budget — surface sharding /
snapshot-reuse rather than silently re-running.

### 5 — Document + green PR

Prune the journey body to load-bearing decisions only (code is now the source of
truth — delete file trees, signatures, resolved threat rows; keep contracts,
parity exclusions, the task checklist as the record). Flip `status: done` +
`done_at`; mark `rolls_up` stories `rolled_up_into: J-NNN`. `gh pr ready`. Post
the **video(s)** to the operator via `SendUserFile` — the AlpenFlight pass video
plus the legacy `flsweb` video when applicable, captioned for side-by-side
parity-checking — with the PR link + Mocked-seams list. **Stop — the operator
merges** `integration/J-NNN` up the line.

## Escalation triggers

Stop and ask the operator (one precise question) when a worker reports: a parity
assertion only passes by changing behavior; this journey breaks another's green;
a `depends_on` artifact is missing despite the dep being done; ported legacy
code has an apparent bug (never silently fix); an AC is unmeetable; or
`gap-hunter` flags a blocker needing a contract/ADR/sacred-cow change. Default
next: `/do-retro` captures the lesson; `/do-plan` re-carves if the journey shape
was wrong.

## Quality bar

- One journey per invocation. `carved: false` is a hard bail.
- Every task runs in a fresh worker context — you never do task work inline.
- The green bar is the real full-chain run — never a mocked-only pass.
- Default real; declared+signed mocks only; undeclared mock = red.
- Schema structural; business rules on aggregates (ADR 0022 directive 2).
- Tasks commit directly to `integration/J-NNN`; never merge red; one PR per journey.
- Prune before done. Cite by file:line / PR# / J-ID, never SHAs.
- Does **not** merge PRs, auto-edit ADRs, or delete issues.

## When done

Journey is `status: done`, the real chain is green, the video is with the
operator, one PR is ready on `integration/J-NNN`. Operator merges; then
`/do-plan next`.
