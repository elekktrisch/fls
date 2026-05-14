# Modernization Workflow

A spec-kit-inspired pipeline for planning and executing a greenfield rewrite of this codebase. Driven by four Claude Code skills under `.claude/skills/`; emits markdown artifacts to this folder.

## Strategic anchors (fixed for this project)

These are baked into the skills via [`00-seed.md`](00-seed.md). They are **not** revisited by the workflow — change the seed if you need them to change.

- **Strategy:** greenfield rewrite of both `flsserver` and `flsweb`. Database is in scope only if a viable data-migration path exists.
- **Coexistence:** parallel build, hard cutover at the end. Old and new do not interoperate at runtime.
- **Target repo layout:** new code lives under a single top-level subtree `next/` (working slug — renamed to the final product slug at cutover via a phase-4 naming story), with sub-folders `next/server/`, `next/web/`, `next/database/`, `next/auth/`, and `next/ops/`. The subtree sits sibling to the existing `flsserver/`/`flsweb/` folders inside this repository.
- **Artifact tracking:** markdown-only for now. GitHub-issue sync deferred until there are stories worth tracking.

Everything else — backend language, frontend framework, database, auth, hosting, observability — is **decided by the workflow** via ADRs.

## The four phases

| # | Skill | Reads | Produces |
|---|---|---|---|
| 1 | `/modernize-discover` | repo source + project docs + e2e tests + seed | [`01-current-state.md`](01-current-state.md) |
| 2 | `/modernize-vision` | phase 1 + interactive elicitation + seed | `02-vision-and-constraints.md` |
| 3 | `/modernize-adrs` | phases 1–2 + interactive decisions | `adrs/0001-*.md`, `adrs/0002-*.md`, ... |
| 4 | `/modernize-decompose` | phases 1–3 | `epics/E-NN-*.md`, `stories/S-NN-*.md` |

Each phase is independently re-runnable; later phases assume earlier ones exist and bail out if they don't.

## How to run

```
/modernize-discover    # produces 01-current-state.md
/modernize-vision      # interactive — produces 02-vision-and-constraints.md
/modernize-adrs        # interactive — produces adrs/*.md
/modernize-decompose   # produces epics/ and stories/
```

Re-running a phase regenerates its artifact in place. Hand-edits to artifacts survive across regenerations only if you commit them between runs — the skill diffs against the committed version.

## File layout

```
docs/modernization/
├── README.md                       (this file)
├── 00-seed.md                      project-specific anchors, sacred cows, glossary
├── 01-current-state.md             phase 1 output: feature inventory + architecture digest
├── 02-vision-and-constraints.md    phase 2 output: target outcomes + non-negotiables
├── adrs/
│   ├── 0001-<topic>.md             one ADR per major decision
│   └── ...
├── epics/
│   └── E-NN-<slug>.md
└── stories/
    └── S-NN-<slug>.md
```

## Why "generic skills + project seed"

The four SKILL.md files in `.claude/skills/` are project-agnostic — they could plan a modernization for any legacy app. Project context lives in [`00-seed.md`](00-seed.md), which the skills read as their first step. If you adapt this workflow to another project, you replace the seed and (almost) nothing else.
