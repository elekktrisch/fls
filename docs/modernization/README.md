# Modernization Workflow

A pipeline for planning **and** executing a greenfield rewrite of this codebase.
Driven by Claude Code skills under `.claude/skills/` plus specialist subagents
under `.claude/agents/`; emits markdown artifacts to this folder and code to
`alpenflight/`.

> **Primary directives** — [ADR 0022](adrs/0022-modernization-primary-directives.md). Read this before any skill file.
>
> 1. **Working software over comprehensive documentation.** Procedure files exist to enable shipping behavior — they're not deliverables.
> 2. **Business logic in the DDD domain, not the database.** Schema is structural; aggregates own the rules.
>
> Skill files target ≤ 200 lines, agent files ≤ 100. When a procedure here disagrees with the directives, the directives win.

## The workflow: the `do-*` suite

The active workflow is the **`do-*` skill suite** — vertical journey slices, each
one SPA screen/route, each provable by a green Playwright run. It superseded the
older phase-by-phase `modernize-*` skills (those sliced by layer, so nothing was
provable end-to-end until a whole layer landed; retired after `do-*` proved out
across J-0/J-0b/J-0c/J-1/J-2/J-3 — J-3 T-15 deleted the `modernize-*` skills +
their 12 specialist agents and pruned the `rolled_up_into:` horizontal stories).

| Skill | Purpose | Trigger |
|---|---|---|
| `/do-plan` | (Re-)shape the remaining backlog into vertical journey slices; maintain a thin value-ordered roadmap and deep-carve ONE journey JIT for `/do-ship`. | `/do-plan [J-NNN \| next]` |
| `/do-ship` | Drive ONE journey to a green PR. Creates the `integration/J-NNN` branch, decides the task list, runs each task in a fresh-context `/do-task` worker, then runs the real legacy→migrate→Keycloak→Playwright gate + video. Stops at a green PR. | `/do-ship J-NNN` |
| `/do-task` | Implement ONE task of a journey in a clean context — TDD the slice, commit directly to the journey integration branch, tick the checklist, stop. | `/do-task J-NNN [T-NN \| next]` |
| `/do-retro` | Improve the suite from what shipping just taught you — tune the do-* skills/agents, propose ADR amendments, re-shape the backlog, memory hygiene, file infra/efficiency journeys. | `/do-retro` |

### Specialist subagents

Defined in `.claude/agents/`. Read-only — they analyze and report; synthesis is the
calling skill's job. The `do-*` suite uses four:

- **`legacy-oracle`** — behavior/parity oracle: how the legacy stacks actually behave for a given slice.
- **`slice-carver`** — carves a journey into provable vertical slices (used by `/do-plan`).
- **`gap-hunter`** — finds coverage/edge gaps before the gate (used by `/do-ship`, `/do-retro`).
- **`e2e-driver`** — Playwright spec/flake wrangler (used by `/do-ship`, `/do-task`, `/do-retro`).

## Strategic anchors (fixed for this project)

These are baked into the skills via [`00-seed.md`](00-seed.md). They are **not** revisited by the workflow — change the seed if you need them to change.

- **Strategy:** greenfield rewrite of both `flsserver` and `flsweb`. Database is in scope only if a viable data-migration path exists.
- **Coexistence:** AlpenFlight is a multi-tenant SaaS — each legacy FLS deployment onboards independently via the export-JAR + UI-upload flow (epic E-15) on its own schedule. No centralized cutover event; old and new run side-by-side per-tenant until that tenant uploads.
- **Target repo layout:** new code lives under a single top-level subtree `alpenflight/` (renamed from `next/` in S-152), with sub-folders `alpenflight/server/`, `alpenflight/web/`, `alpenflight/database/`, `alpenflight/auth/`, `alpenflight/ops/`, `alpenflight/migration-bundle/`, and `alpenflight/migration-tool/`. The subtree sits sibling to the existing `flsserver/`/`flsweb/` folders inside this repository.
- **Artifact tracking:** markdown-only for now. GitHub-issue sync deferred until there are stories worth tracking.

Everything else — backend language, frontend framework, database, auth, hosting, observability — is **decided by the workflow** via ADRs.

## Integration-branch workflow

`/do-ship` drives each journey on its own `integration/J-NNN` branch:

1. `/do-ship J-NNN` creates `integration/J-NNN` off the latest `main` (carrying any `/do-retro` suite edits + riders).
2. Each task runs in a fresh `/do-task` worker that commits **directly** to `integration/J-NNN`.
3. The journey gate (real legacy→migrate→Keycloak→Playwright chain + video + proof gallery) runs at journey level, not per task.
4. `/do-ship` stops at a **green PR** `integration/J-NNN` → `main` for the operator to review + merge. The operator merges; the suite does not squash-merge on the operator's behalf.

ADR amendments still commit to `main` directly even when a journey is on an integration branch — governance is cross-cutting.

## File layout

```
docs/modernization/
├── README.md                       (this file)
├── 00-seed.md                      project-specific anchors, sacred cows, glossary
├── 01-current-state.md             feature inventory + architecture digest
├── 02-vision-and-constraints.md    target outcomes + non-negotiables
├── legacy-migration-plan.md        single source of truth: every legacy DB table → destination + semantics + owning journey
├── adrs/
│   ├── 0001-<topic>.md             one ADR per major decision
│   └── ...
├── epics/
│   └── E-NN-<slug>.md
└── stories/
    ├── _ORDER.md                   value-ordered journey roadmap; references stay valid after archive
    ├── _BOYSCOUT.md                fix-forward rider backlog (mechanical work that rides the next journey)
    ├── J-NNN-<slug>.md             journey files — the active unit of work (one screen/route, one green Playwright run)
    ├── S-NNN-<slug>.md             horizontal story files not yet absorbed by a journey
    ├── ...
    └── implemented/                finalized stories, reference-only history (kept; not pruned)
        ├── S-NNN-<slug>.md
        └── ...
```

**Story lifecycle under `do-*`.** Horizontal `S-NNN` stories are the original
decomposition; a journey that fully absorbs one stamps it `rolled_up_into: J-NNN`
(`/do-plan`). `/do-retro` later **prunes** the `rolled_up_into:` stories (their
content now lives in the journey) — that prune is mechanical and rides the next
journey as a boyscout rider. The 47 `implemented/` stories and their docs **stay
as history** and are never pruned.

## Why "generic skills + project seed"

The SKILL.md files in `.claude/skills/` are project-agnostic — they could plan and
execute a modernization for any legacy app. The specialist agents in
`.claude/agents/` are also general personas, not project-specific (here applied to
the FLS → AlpenFlight rewrite). Project context lives in [`00-seed.md`](00-seed.md),
which the skills read as their first step. If you adapt this workflow to another
project, you replace the seed and (almost) nothing else.
