# Modernization Workflow

A spec-kit-inspired pipeline for planning **and** executing a greenfield rewrite of this codebase. Driven by six Claude Code skills under `.claude/skills/` plus five specialist subagents under `.claude/agents/`; emits markdown artifacts to this folder and (in phase 6) code to `next/`.

## Strategic anchors (fixed for this project)

These are baked into the skills via [`00-seed.md`](00-seed.md). They are **not** revisited by the workflow — change the seed if you need them to change.

- **Strategy:** greenfield rewrite of both `flsserver` and `flsweb`. Database is in scope only if a viable data-migration path exists.
- **Coexistence:** parallel build, hard cutover at the end. Old and new do not interoperate at runtime.
- **Target repo layout:** new code lives under a single top-level subtree `next/` (working slug — renamed to the final product slug at cutover via a phase-4 naming story), with sub-folders `next/server/`, `next/web/`, `next/database/`, `next/auth/`, and `next/ops/`. The subtree sits sibling to the existing `flsserver/`/`flsweb/` folders inside this repository.
- **Artifact tracking:** markdown-only for now. GitHub-issue sync deferred until there are stories worth tracking.

Everything else — backend language, frontend framework, database, auth, hosting, observability — is **decided by the workflow** via ADRs.

## The six phases

| # | Skill | Mode | Reads | Produces |
|---|---|---|---|---|
| 1 | `/modernize-discover` | batch | repo source + project docs + e2e tests + seed | [`01-current-state.md`](01-current-state.md) |
| 2 | `/modernize-vision` | interactive | phase 1 + elicitation + seed | `02-vision-and-constraints.md` |
| 3 | `/modernize-adrs` | interactive | phases 1–2 + decisions | `adrs/0001-*.md`, `adrs/0002-*.md`, ... |
| 4 | `/modernize-decompose` | batch | phases 1–3 | `epics/E-NN-*.md`, `stories/S-NNN-*.md`, `_ORDER.md` |
| 5 | `/modernize-refine <S-NNN>` | **per-story** | one story + ADRs + 5 subagents | new sections + `refined: true` in that story file |
| 6 | `/modernize-implement <S-NNN>` | **per-story** | one refined story | code in `next/`, tests, `status: done` in that story file |

Phases 1–4 are one-shot planning. Phases 5–6 are per-story execution — invoked once per story, in order from `_ORDER.md`. The split exists because:
- **Just-in-time refinement** keeps specs fresh: refining all 122 stories up-front means most refinement is stale by the time it's read.
- **One story per implement run** keeps blast radius tight: a single story arrives committable; a batch of stories arrives as a tangle.

## Specialist subagents (used by phase 5)

Defined in `.claude/agents/`, invoked in parallel by `/modernize-refine`:

| Subagent | Concern |
|---|---|
| `requirements-engineer` | Edge cases, hidden requirements, scope clarifications, NFR call-outs |
| `solution-architect` | Module layout, domain model, API surface, alternatives considered |
| `security-engineer` | Threat model, authorization, validation, PII, audit events, multi-tenancy |
| `qa-engineer` | Test pyramid, specific test cases, parity-test design, fixtures, coverage gaps |
| `performance-engineer` | Hot paths, indexes, N+1 risks, caching, latency budget |

These are read-only — they analyze and report. Synthesis into the story file is the refine skill's job. The five agents are also reusable outside the modernization workflow.

## How to run

```bash
# Planning (one-shot, in order):
/modernize-discover    # produces 01-current-state.md
/modernize-vision      # interactive — produces 02-vision-and-constraints.md
/modernize-adrs        # interactive — produces adrs/*.md
/modernize-decompose   # produces epics/, stories/, _ORDER.md

# Execution (per story, repeat until cutover):
/modernize-refine S-001     # spawns 5 specialists, adds refinement sections + sets refined: true
/modernize-implement S-001  # writes code, runs tests, sets status: done

# Then the next story:
/modernize-refine S-002
/modernize-implement S-002
# ...
```

Re-running a planning phase (1–4) regenerates its artifact in place. Re-running `/modernize-refine` on a story replaces its refinement sections atomically. Re-running `/modernize-implement` on a story that's already `done` is refused — explicitly flip status if you want to redo work.

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
    ├── _ORDER.md                   topological execution order (phase 4 output)
    ├── S-NNN-<slug>.md             story files — extended in-place by phase 5 + 6
    └── ...
```

A story file evolves over its lifetime:
1. **After phase 4** — `status: todo`, body has Context / Acceptance / Tasks / Notes.
2. **After phase 5** — `refined: true`, body gains Design notes / Edge cases / Security plan / Test plan / Performance plan (and Open design questions if conflicts surfaced).
3. **During phase 6** — `status: in_progress`, `started_at: <date>`.
4. **After phase 6** — `status: done`, `done_at: <date>`, code lives under `next/`.

## Why "generic skills + project seed"

The SKILL.md files in `.claude/skills/` are project-agnostic — they could plan and execute a modernization for any legacy app. The five specialist agents in `.claude/agents/` are also general personas, not FLS-specific. Project context lives in [`00-seed.md`](00-seed.md), which the skills read as their first step. If you adapt this workflow to another project, you replace the seed and (almost) nothing else.
