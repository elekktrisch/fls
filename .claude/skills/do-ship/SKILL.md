---
name: do-ship
description: Build ONE vertical journey end-to-end — analyze, implement (TDD), prove with a real legacy→migrate→Keycloak→Playwright run + video, document, open a green PR. Solo inline; escalate on signal. Stops at a green PR for the operator to merge. Trigger: /do-ship J-NNN.
---

# do-ship — ship one vertical journey

Take one carved journey (`J-NNN`) and make it real: code the whole vertical
(DB→domain→API→UI), prove it with a green Playwright run against real migrated
legacy data, and open a ready-for-review PR. The operator merges.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md)
first — both directives govern every decision. Schema is structural only;
business rules live on aggregates.

**Search posture.** Default to MCP servers over raw grep: use the IntelliJ MCP
(`search_in_files_by_regex`, `search_in_files_by_text`, `find_files_by_glob`,
`search_symbol`, `get_symbol_info`) for code search/navigation and the
**codebase-memory-mcp** for prior-art recall. Fall back to `Grep`/`Glob` only
when no MCP server is connected.

## The core idea

One journey at a time (no fleet — a vertical diff touches DB→UI and collides
with any parallel one). Solo inline by default; spin up a subagent only on a
real signal:

| Agent | Fires when |
|---|---|
| `legacy-oracle` | The exact legacy behavior the spec must match isn't derivable from code alone (parity-sensitive screen). |
| `e2e-driver` | The Playwright spec / proof chain gets gnarly — selectors, fixtures, auth, the migration snapshot, flake. |
| `gap-hunter` | At the gate, always — adversarial check that the green is honest (spawn 2-3, majority-vote). |

The standing 12-agent panel is gone. These are on-demand tools.

## Journey ID + branch

Resolve `J-NNN` from the arg, or from a `journey/J-NNN-*` branch. Bail if the
journey file isn't at `docs/modernization/stories/J-NNN-*.md`, isn't
`carved: true` ("run /do-plan J-NNN first"), or isn't `status: todo`
(in_progress → resume; done → refuse). Every `depends_on` journey must be
`done` + its PR `MERGED`. Branch: `git checkout -b journey/J-NNN-<slug>`.

## The done bar — a real, honest green

A journey is **done only when its Playwright spec drives the real UI end to
end and passes** — first on a clean seed, then on real legacy data migrated
into AlpenFlight. The video of the passing run is the acceptance artifact.

**Red is the inner work-list, not a wall.** `/do-ship` is never done while the
chain is red; the red cases ARE the to-do list. "Not blocked" means no external
gate stops you — the green bar is **self-imposed and absolute. A journey never
merges red.**

## The proof chain

- **Journey-0** (`journey0: true`): your job is to stand up the *thinnest* whole
  chain — orchestrate legacy-up → migrate → Keycloak realm import → Postgres →
  real Playwright (`playwright.config.next.ts`) for ONE already-built screen.
  Delegate the wiring to `e2e-driver`. Build the minimum that proves the
  architecture, not a framework.
- **Every later journey extends the chain**: add this entity's legacy seed
  (enough to exercise [happy] + [key-error]) + its per-entity migration mapper +
  the real-stack spec. The old migration lump (S-016/S-139/S-141/S-109) lives
  here now, one entity at a time.

## Two-tier run cadence

- **Inner loop (fast):** iterate against mock-auth + a Testcontainers backend
  (or `page.route` mocks for FE-only work). Tight feedback; respects the
  wallclock budget.
- **Gate (real):** the full legacy→migrate→Keycloak→real chain runs **once at
  the green-PR gate** and as a **required CI check** on the PR.

## Mock governance

- **Default real.** Happy path + key error cases run fully real at the gate — no
  mocking, no signoff needed.
- Any mocked seam (edge/error specs only) carries an inline
  `@mocked: <seam> — <reason>` tag AND appears in a **"Mocked seams"** list in
  the PR body. Surface that list and ask the operator for **one signoff** at the
  gate. `gap-hunter` cross-checks the list against the diff. **Undeclared or
  unsigned mocks make the chain red.**

## Procedure

### 1 — Load context

Read the journey file, its `rolls_up` stories + their refinement, `adr_refs`,
the legacy screen(s) it replaces, `00-seed.md`. For parity-sensitive screens,
dispatch `legacy-oracle` now to get the behavior oracle the spec is written
against. Recall prior art via the codebase-memory-mcp. For libraries
(Angular/Spring/Playwright/NgRx), fetch current docs via Context7.

### 2 — Status + issue + branch

Flip `status: in_progress` + `started_at`. Create a GitHub issue
(`J-NNN: <title>`) if `gh` + remote available; stamp `github_issue`. Create the
branch; initial commit `#N: start`.

### 3 — Spec stub first

Author the journey's Playwright spec **structure** — routes, selectors, flow
steps — with thin assertions (delegate to `e2e-driver` if gnarly). This commits
the screen's shape early. Watch it fail for the right reason. Commit
`#N: spec stub for J-NNN`. Don't push past red.

### 4 — Implement the vertical, per work-package

Outside-in, driven by the spec. Order:
1. **DB migration** if the model changed (Flyway). Structural only per ADR 0022
   directive 2 — business rules go on aggregates, not CHECK/trigger/generated.
2. **Backend slice:** entity → repository → service → controller + unit tests.
   `@PreAuthorize` + `@TenantId` per the oracle's tenancy rules.
3. **Frontend slice:** Signal Store → component → route + logic unit tests,
   consuming the regenerated client.
4. **Proof-chain contribution:** legacy seed + per-entity mapper for this
   journey (delegate migration wiring to `e2e-driver`).
5. Iterate against the fast inner loop to green; each green turn is a commit.

Commit per work-package (target 3-8). Subject `#N: <summary>`. First push after
the backend slice is locally green → **draft PR** (`gh pr create --draft`).
Watch CI in the background; fix red before resuming. Don't `--no-verify` / force-push.

### 5 — Thicken to the gate

Promote the spec to **full real assertions** (from the oracle). Run the full
chain (`e2e-driver` owns it): legacy seed → migrate → Keycloak → real
Playwright, both fidelities green, **video retained on pass**. If red, the red
cases are work — return to step 4. Honor the ≥5-min wallclock budget: surface
sharding / snapshot-reuse options rather than silently re-running.

### 6 — Gate check (gap-hunter)

Spawn `gap-hunter` ×2-3 against `git diff <base>...HEAD` + the spec + the PR
Mocked-seams list. Majority `real: false` → fix the findings inline (or, if it's
a contract/ADR/sacred-cow conflict, escalate per below). Re-run until the chain
is honestly green.

### 7 — Document + green PR

Prune the journey body to load-bearing decisions only (the code is now the
source of truth — delete file trees, method signatures, resolved threat rows,
task lists). Flip `status: done` + `done_at`; `git mv` to `implemented/` in the
same commit. Mark fold-in `rolls_up` stories `rolled_up_into: J-NNN` + move them.
`gh pr ready`. Post the **video** to the operator via `SendUserFile` with the PR
link + the Mocked-seams list (if any) for signoff. **Stop here — the operator
merges.**

## Escalation triggers

Stop and ask the operator (one precise question) when: a parity assertion can
only pass by changing behavior; this journey breaks another's green; a
`depends_on` artifact is missing despite the dep being done; legacy code being
ported has an apparent bug (never silently fix); an AC is unmeetable; or
`gap-hunter` flags a blocker that needs a contract/ADR/sacred-cow change.
Default next action: `/do-retro` captures the lesson; `/do-plan` re-carves if
the journey shape was wrong.

## Quality bar

- One journey per invocation. `carved: false` is a hard bail.
- The green bar is the real full-chain run — never a mocked-only pass.
- Spec-stub-first; thicken to real assertions at the gate.
- Default real; declared+signed mocks only; undeclared mock = red.
- Schema structural; business rules on aggregates (ADR 0022 directive 2).
- Commit per work-package; push at green boundaries; don't push past red.
- Prune the journey body before done. Cite by file:line / PR# / J-ID, never SHAs.
- The skill does **not** merge PRs, auto-edit ADRs, or delete issues.

## When done

Journey is `status: done`, the real chain is green, the video is with the
operator, the PR is ready-for-review. Operator merges; then `/do-plan next`.
