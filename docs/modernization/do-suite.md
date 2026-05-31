# The `do-*` suite — vertical-slice modernization

A lighter, vertical replacement for the `modernize-*` workflow. Where
`modernize-*` slices the rewrite **horizontally** (whole repo layer, whole API
layer — nothing provable until ~20 stories of a layer land, integration gaps
surface as late rework), `do-*` slices **vertically**: each unit is one
user-facing path, thin but whole, proven green before the next starts.

Status: **coexists** with `modernize-*`. Prove it on 2-3 real journeys, then
`/do-retro` files the cleanup that retires the superseded `modernize-*` skills.

## The three skills

| Skill | Does |
|---|---|
| `/do-plan` | (Re-)shape the 113 `todo` stories into vertical **journeys**. Thin value-ordered roadmap (the new `_ORDER`), then deep-carve ONE journey JIT. Journey = the new story shape (`J-NNN`); horizontal stories *roll up* so refinement isn't lost. The 47 `implemented/` stay as history. |
| `/do-ship` | Build ONE journey end-to-end: analyze → implement (TDD) → prove → document → green PR. Solo inline; escalate on signal. Stops at a green PR; the **operator merges**. |
| `/do-retro` | Improve the suite from what shipping taught: tune skills/agents, propose ADR amendments, re-shape the backlog, memory hygiene, file infra/efficiency journeys. Purely manual; reconstructs lessons from git + PRs. |

## The four agents

`legacy-oracle` (extracts the exact legacy behavior a journey must match),
`slice-carver` (proposes the journey grouping for `/do-plan`), `gap-hunter`
(adversarial: proves a green is hollow), `e2e-driver` (authors the Playwright
spec + owns the proof chain). The standing 12-agent panel is gone — these are
on-demand escalation tools.

## What a journey is

**One SPA screen/route, full CRUD, driven DB→domain→API→UI**, provable by one
green Playwright run. Headless work (rules engine, jobs, integrations) never
gets its own journey — it's pulled in by the screen that uses it (real screen →
admin screen → test-env-only admin/test affordance → else escalate).

## The done bar — a real, honest green

A journey is done only when its Playwright spec drives the **real UI** end to
end and passes — first on a clean seed, then on **real legacy data migrated into
AlpenFlight**. The pass-video is the acceptance artifact, surfaced to the operator.

- **Proof chain:** Journey-0 stands up the thinnest whole chain (legacy-up →
  migrate → Keycloak → real Playwright) for one already-built screen; every
  later journey extends it with its own seed + per-entity mapper + spec. The
  horizontal migration lump (S-016/S-139/S-141/S-109) **dissolves** into these
  per-journey contributions.
- **Two-tier cadence:** fast mocked inner loop during build; full real chain
  once at the green-PR gate (and as a required CI check).
- **Red = the inner work-list, not a wall.** `/do-ship` is never done while red;
  the green bar is self-imposed and absolute. **A journey never merges red.**
- **Mock governance:** happy + key-error cases run fully real. Edge/error mocks
  carry an inline `@mocked:` tag + a PR "Mocked seams" list + one operator
  signoff; `gap-hunter` cross-checks; undeclared mocks count as red.

## Conventions

- **Search posture:** default to MCP servers — the IntelliJ MCP for code
  search/navigation, the `codebase-memory-mcp` for prior-art recall — over raw
  grep. Fall back to `Grep`/`Glob` only when no MCP is connected.
- Inherits the sound parts of `modernize-implement`: work-package commits,
  draft→ready PR, CI watch, the ≥5-min wallclock perf-budget escalation.
- One journey at a time (no fleet — vertical diffs collide).
- [ADR 0022](adrs/0022-modernization-primary-directives.md) governs unchanged:
  working software > docs; business logic on aggregates, not the schema.

## Open assumptions (flagged for review)

1. The `codebase-memory-mcp` tool names aren't pinned in the agents' `tools:`
   frontmatter (only the IntelliJ MCP tools are) — the MCP is referenced in
   prose; `/do-retro` adds exact tool names once it's connected.
2. `/do-plan` supersedes S-016/S-139/S-141/S-109 by reshaping, rather than those
   being implemented as-is.
3. Greenfield/freemium journeys (E-15, no legacy) degrade migration + legacy-oracle
   to N/A; the clean-seed real run is the sole gate.
