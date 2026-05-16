# Modernization Workflow

A spec-kit-inspired pipeline for planning **and** executing a greenfield rewrite of this codebase. Driven by Claude Code skills under `.claude/skills/` plus specialist subagents under `.claude/agents/`; emits markdown artifacts to this folder and (in phase 6) code to `next/`.

**Skill set:** the nine numbered phases (discover → vision → adrs → decompose → refine → implement → review → rework → finalize) plus three orchestration extensions for throughput (`/modernize-refine-ahead`, `/modernize-sweep-finalize`, `/modernize-fleet`) — see [Orchestration extensions](#orchestration-extensions) below.

## Strategic anchors (fixed for this project)

These are baked into the skills via [`00-seed.md`](00-seed.md). They are **not** revisited by the workflow — change the seed if you need them to change.

- **Strategy:** greenfield rewrite of both `flsserver` and `flsweb`. Database is in scope only if a viable data-migration path exists.
- **Coexistence:** parallel build, hard cutover at the end. Old and new do not interoperate at runtime.
- **Target repo layout:** new code lives under a single top-level subtree `next/` (working slug — renamed to the final product slug at cutover via a phase-4 naming story), with sub-folders `next/server/`, `next/web/`, `next/database/`, `next/auth/`, and `next/ops/`. The subtree sits sibling to the existing `flsserver/`/`flsweb/` folders inside this repository.
- **Artifact tracking:** markdown-only for now. GitHub-issue sync deferred until there are stories worth tracking.

Everything else — backend language, frontend framework, database, auth, hosting, observability — is **decided by the workflow** via ADRs.

## The nine phases

| # | Skill | Mode | Reads | Produces |
|---|---|---|---|---|
| 1 | `/modernize-discover` | batch | repo source + project docs + e2e tests + seed | [`01-current-state.md`](01-current-state.md) |
| 2 | `/modernize-vision` | interactive | phase 1 + elicitation + seed | `02-vision-and-constraints.md` |
| 3 | `/modernize-adrs` | interactive | phases 1–2 + decisions | `adrs/0001-*.md`, `adrs/0002-*.md`, ... |
| 4 | `/modernize-decompose` | batch | phases 1–3 | `epics/E-NN-*.md`, `stories/S-NNN-*.md`, `_ORDER.md` |
| 5 | `/modernize-refine <S-NNN>` | **per-story** | one story + ADRs + 5 subagents | new sections + `refined: true` in that story file |
| 6 | `/modernize-implement <S-NNN>` | **per-story** | one refined story + Step 6.7 self-review consult | code in `next/`, tests, `status: done`, GitHub issue + draft→ready-for-review PR |
| 7 | `/modernize-review <S-NNN>` | **per-story** | one implemented story + 4 reviewer subagents | `## Review` section + `reviewed: true` + GitHub issues for blockers |
| 8 | `/modernize-rework <S-NNN> [--bold]` | **per-story** | one reviewed story + per-finding triage (interactive or auto in `--bold`) | annotations on review bullets, follow-up story files for deferred items |
| 9 | `/modernize-finalize <S-NNN>` | **per-story** | one rework-clean story + ADR amendments + operator confirm | pre-merge bookkeeping commit on PR branch (stamps `merged: true`, moves story file to `stories/implemented/`), squash-merge, branch delete, issue close |

Phases 1–4 are one-shot planning. Phases 5–9 are per-story execution — invoked once per story, in order from `_ORDER.md`. The split exists because:
- **Just-in-time refinement** keeps specs fresh: refining all 122 stories up-front means most refinement is stale by the time it's read.
- **One story per implement run** keeps blast radius tight: a single story arrives committable; a batch of stories arrives as a tangle.
- **Single-story review/rework/finalize** keeps the audit trail per story coherent.

The throughput orchestration extensions (`/modernize-refine-ahead`, `/modernize-sweep-finalize`, `/modernize-fleet`) wrap these per-story skills without changing the per-story state machine.

## Specialist subagents

Defined in `.claude/agents/`. Read-only — they analyze and report; synthesis into the story file is the calling skill's job.

**Refine-time (phase 5, invoked in parallel by `/modernize-refine`):**

| Subagent | Concern |
|---|---|
| `requirements-engineer` | Edge cases, hidden requirements, scope clarifications, NFR call-outs |
| `solution-architect` | Module layout, domain model, API surface, alternatives considered |
| `security-engineer` | Threat model, authorization, validation, PII, audit events, multi-tenancy |
| `qa-engineer` | Test pyramid, specific test cases, parity-test design, fixtures, coverage gaps |
| `performance-engineer` | Hot paths, indexes, N+1 risks, caching, latency budget |

**Review-time (phase 7, invoked in parallel by `/modernize-review`):**

| Subagent | Concern |
|---|---|
| `maintainability-reviewer` | Layering, clarity, tests, ADR conformance, deps, migrations |
| `parity-reviewer` | Behavioral parity vs. legacy oracle; tests anchored on behavior not API shape |
| `security-reviewer` | Authz, validation, PII, audit events, tenancy |
| `usability-reviewer` | UI consistency, i18n, loading/empty/error states, a11y, responsive |

**Implement-time consults (phase 6, invoked one-shot by `/modernize-implement` Step 4.5):**

| Subagent | Concern |
|---|---|
| `implementation-architect` | Patch design when a fork surfaces the refinement didn't cover |
| `legacy-investigator` | Disambiguate parity-sensitive legacy behavior at file:line |

`solution-architect`, `security-engineer`, `qa-engineer`, `performance-engineer`, `requirements-engineer` from the refine bank are also reusable in implement-time consults. The agents are general personas, not project-specific (here applied to the FLS → AlpenFlight rewrite) — they're reusable outside the modernization workflow.

## How to run

```bash
# Planning (one-shot, in order):
/modernize-discover    # produces 01-current-state.md
/modernize-vision      # interactive — produces 02-vision-and-constraints.md
/modernize-adrs        # interactive — produces adrs/*.md
/modernize-decompose   # produces epics/, stories/, _ORDER.md

# Per-story flow (refine → implement → review → rework? → finalize):
/modernize-refine S-001     # spawns 5 specialists, adds refinement sections + sets refined: true
/modernize-implement S-001  # writes code, runs tests, self-reviews, sets status: done, opens PR
/modernize-review S-001     # 4 reviewers, writes ## Review section, files blocker issues
/modernize-rework S-001     # triages findings (or --bold to auto-decide nudges + simple improvements)
# operator fixes address-now items, pushes, re-reviews if needed
/modernize-finalize S-001   # squash-merge, branch delete, issue close

# Then the next story (or use throughput extensions below):
/modernize-refine S-002
# ...

# Throughput extensions (optional, layer on top):
/modernize-refine-ahead 5   # speculatively refine the next 5 unblocked stories
/modernize-fleet 3          # run refine→implement→review on 3 stories concurrently in worktrees
/modernize-sweep-finalize   # auto-finalize every story that satisfies the gate (no judgment calls)
```

Re-running a planning phase (1–4) regenerates its artifact in place. Re-running `/modernize-refine` on a story replaces its refinement sections atomically. Re-running `/modernize-implement` on a story that's already `done` is refused — explicitly flip status if you want to redo work.

## Orchestration extensions

Three skills layer on top of the nine phases to improve throughput without compromising the per-story state-machine guarantees. All are opt-in.

| Skill | Purpose | Typical use |
|---|---|---|
| `/modernize-refine-ahead [N]` | Speculative buffer-fill — refines the next N unblocked stories ahead of when implement needs them. Stamps `refined_speculative: true` so implement can re-refine if stale. | Run before a fleet batch, or on a `/loop` cadence to keep a rolling buffer of refined stories. |
| `/modernize-sweep-finalize` | Daemon-style finalize — scans all stories, auto-finalizes anything that satisfies the gate without judgment calls. Defers ADR amendments and `CHANGES_REQUESTED` PRs. | Wrap in `/loop 30m /modernize-sweep-finalize` or `/schedule` for unattended cadence. |
| `/modernize-fleet [N]` | Parallel-fleet orchestrator — dispatches up to N independent unblocked stories to isolated worktrees and runs refine→implement→review concurrently. Batches operator checkpoints. | Run on the long tail of leaf stories (CRUDs, observability, scheduled jobs). Foundational stories stay JIT. |

Two quality gates were added to the existing skills to support these:

- **`/modernize-implement` Step 6.7 — self-review gate.** A single `maintainability-reviewer` consult against the diff before the status-flip push, scoped to blockers only. Catches the most common review→rework blockers at source and cuts the average review→rework loop from ~1.5 cycles to ~1.0.
- **`/modernize-rework --bold`** — opt-in auto-triage of nudges and one-line one-file improvements. Blockers and ambiguous improvements still prompt. Annotations distinguish `[auto-accepted]` / `[auto-in-rework]` from operator decisions so the audit trail is clean.

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
    ├── _ORDER.md                   topological execution order (phase 4 output); references stay valid after archive
    ├── S-NNN-<slug>.md             story files in flight — extended in-place by phases 5–8
    ├── ...
    └── implemented/                finalized stories, moved here by phase 9; reference-only
        ├── S-NNN-<slug>.md
        └── ...
```

Selection skills (`/modernize-refine-ahead`, `/modernize-fleet`, `/modernize-sweep-finalize`) enumerate only the top-level `stories/S-*.md` glob — implemented stories never re-appear as work candidates. Skills that need to read an implemented story (e.g. `/modernize-refine` resolving a `depends_on:` predecessor, or `/modernize-rework` minting the next free `S-NNN`) glob both locations.

A story file evolves over its lifetime:
1. **After phase 4** — `status: todo`, body has Context / Acceptance / Tasks / Notes.
2. **After phase 5** (`refined: true`) — body gains Design notes / Edge cases / Security plan / Test plan / Performance plan (and Open design questions if conflicts surfaced). Speculative variant adds `refined_speculative: true`.
3. **During phase 6** — `status: in_progress`, `started_at: <date>`, `github_issue:`, `github_pr:` stamped.
4. **After phase 6** — `status: done`, `done_at: <date>`, code lives under `next/`.
5. **After phase 7** (`reviewed: true`) — body gains `## Review` section with maintainability / parity / security / usability findings; `review_outcome` is `pass` / `improvements-only` / `blockers`.
6. **After phase 8** (`reworked: true`) — `## Review` bullets annotated `[in-rework]` / `[deferred → S-XXX]` / `[accepted: …]` (or `[auto-*]` variants in `--bold` mode); follow-up story files filed.
7. **After phase 9** (`merged: true`) — `merged_at` stamped, PR squash-merged to `main`, branch gone, issue closed, **and the story file is moved from `stories/S-NNN-*.md` to `stories/implemented/S-NNN-*.md`** as part of the pre-merge bookkeeping commit (so the squash carries the move and `main` sees exactly one commit per finalized story). `merge_commit` is not stamped — recoverable via `git log -- docs/modernization/stories/implemented/S-NNN-*.md` if needed.

## Why "generic skills + project seed"

The SKILL.md files in `.claude/skills/` are project-agnostic — they could plan and execute a modernization for any legacy app. The specialist agents in `.claude/agents/` are also general personas, not project-specific (here applied to the FLS → AlpenFlight rewrite). Project context lives in [`00-seed.md`](00-seed.md), which the skills read as their first step. If you adapt this workflow to another project, you replace the seed and (almost) nothing else.
