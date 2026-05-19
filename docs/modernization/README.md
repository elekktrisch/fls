# Modernization Workflow

A spec-kit-inspired pipeline for planning **and** executing a greenfield rewrite of this codebase. Driven by Claude Code skills under `.claude/skills/` plus specialist subagents under `.claude/agents/`; emits markdown artifacts to this folder and (in phase 6) code to `alpenflight/`.

> **Primary directives** — [ADR 0022](adrs/0022-modernization-primary-directives.md). Read this before any skill file.
>
> 1. **Working software over comprehensive documentation.** Procedure files exist to enable shipping behavior — they're not deliverables.
> 2. **Business logic in the DDD domain, not the database.** Schema is structural; aggregates own the rules.
>
> Skill + agent files target ≤ 200 / ≤ 100 lines. When a procedure here disagrees with the directives, the directives win.

**Skill set:** the seven numbered phases (discover → vision → adrs → decompose → refine → implement → finalize) plus the operator-invoked `/modernize-rework` side path, plus three orchestration extensions for throughput (`/modernize-refine-ahead`, `/modernize-sweep-finalize`, `/modernize-fleet`) — see [Orchestration extensions](#orchestration-extensions) below.

## Strategic anchors (fixed for this project)

These are baked into the skills via [`00-seed.md`](00-seed.md). They are **not** revisited by the workflow — change the seed if you need them to change.

- **Strategy:** greenfield rewrite of both `flsserver` and `flsweb`. Database is in scope only if a viable data-migration path exists.
- **Coexistence:** AlpenFlight is a multi-tenant SaaS — each legacy FLS deployment onboards independently via the export-JAR + UI-upload flow (epic E-15) on its own schedule. No centralized cutover event; old and new run side-by-side per-tenant until that tenant uploads.
- **Target repo layout:** new code lives under a single top-level subtree `alpenflight/` (working slug — rename to `alpenflight/` tracked by S-152), with sub-folders `alpenflight/server/`, `alpenflight/web/`, `alpenflight/database/`, `alpenflight/auth/`, `alpenflight/ops/`, `alpenflight/migration-bundle/`, and `alpenflight/migration-tool/`. The subtree sits sibling to the existing `flsserver/`/`flsweb/` folders inside this repository.
- **Artifact tracking:** markdown-only for now. GitHub-issue sync deferred until there are stories worth tracking.

Everything else — backend language, frontend framework, database, auth, hosting, observability — is **decided by the workflow** via ADRs.

## The seven phases

| # | Skill | Mode | Reads | Produces |
|---|---|---|---|---|
| 1 | `/modernize-discover` | batch | repo source + project docs + e2e tests + seed | [`01-current-state.md`](01-current-state.md) |
| 2 | `/modernize-vision` | interactive | phase 1 + elicitation + seed | `02-vision-and-constraints.md` |
| 3 | `/modernize-adrs` | interactive | phases 1–2 + decisions | `adrs/0001-*.md`, `adrs/0002-*.md`, ... |
| 4 | `/modernize-decompose` | batch | phases 1–3 | `epics/E-NN-*.md`, `stories/S-NNN-*.md`, `_ORDER.md` |
| 5 | `/modernize-refine <S-NNN>` | **per-story** | one story + ADRs + 5 subagents | new sections + `refined: true` in that story file |
| 6 | `/modernize-implement <S-NNN>` | **per-story** | one refined story + Step 7 reviewer panel + auto-fix loop | code in `alpenflight/`, tests, `status: done`, GitHub issue + draft→ready-for-review PR. Reviewer findings (maintainability + security + tech-writer/usability + parity-when-applicable) auto-fixed inline; escalates to operator only on scope/design pivots. |
| 7 | `/modernize-finalize <S-NNN>` | **per-story** | one implemented story + ADR amendments + operator confirm | docs-prune pass (delete prose the code now sources; carve out future-story plans + `stories/implemented/` archive; surface unclear cases to operator), pre-merge bookkeeping commit on PR branch (stamps `merged: true`), squash-merge, branch delete, issue close |

Phases 1–4 are one-shot planning. Phases 5–7 are per-story execution — invoked once per story, in order from `_ORDER.md`. The split exists because:
- **Just-in-time refinement** keeps specs fresh: refining all 122 stories up-front means most refinement is stale by the time it's read.
- **One story per implement run** keeps blast radius tight: a single story arrives committable; a batch of stories arrives as a tangle.
- **Single-story finalize** keeps the audit trail per story coherent.

**Side skill — `/modernize-rework <S-NNN>`** — operator-invoked only. Use when the implement auto-fix loop escalates (couldn't converge after 2 rounds; reviewer flagged something that needs scope/design pivot), or when the operator decides post-hoc that a shipped story's shape needs revisiting. Not part of the linear phase progression.

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

**Review-time (phase 6 Step 7, invoked in parallel by `/modernize-implement`'s reviewer panel + auto-fix loop):**

| Subagent | Concern | Spawn when |
|---|---|---|
| `maintainability-reviewer` | Layering, clarity, tests, ADR conformance, deps, migrations | always |
| `security-reviewer` | Authz, validation, PII, audit events, tenancy | not `is_docs_only` |
| `parity-reviewer` | Behavioral parity vs. legacy oracle; tests anchored on behavior not API shape | `parity_test` non-empty OR diff touches `flsserver/` / `flsweb/` |
| `usability-reviewer` | UI consistency, i18n, loading/empty/error states, a11y, responsive | `has_frontend` (real UI changes, not codegen) |
| `tech-writer-reviewer` | Cross-doc consistency, stale citations, originator-story TODO bullets | NOT `has_frontend` (replaces usability for backend / docs-only diffs) |

**Implement-time consults (phase 6, invoked one-shot by `/modernize-implement` Step 4.5):**

| Subagent | Concern |
|---|---|
| `implementation-architect` | Patch design when a fork surfaces the refinement didn't cover |
| `legacy-investigator` | Disambiguate parity-sensitive legacy behavior at file:line |

**Finalize-time consult (phase 7 Step 2.5, invoked by `/modernize-finalize`):**

| Subagent | Concern |
|---|---|
| `tech-writer-reviewer` | Walks PR-touched docs + grep-discovered stale-citation hits; categorises each section as auto-delete / keep / surface-to-operator. |

`solution-architect`, `security-engineer`, `qa-engineer`, `performance-engineer`, `requirements-engineer` from the refine bank are also reusable in implement-time consults. The agents are general personas, not project-specific (here applied to the FLS → AlpenFlight rewrite) — they're reusable outside the modernization workflow.

## How to run

```bash
# Planning (one-shot, in order):
/modernize-discover    # produces 01-current-state.md
/modernize-vision      # interactive — produces 02-vision-and-constraints.md
/modernize-adrs        # interactive — produces adrs/*.md
/modernize-decompose   # produces epics/, stories/, _ORDER.md

# Per-story flow (refine → implement → finalize):
/modernize-refine S-001     # spawns 5 specialists, adds refinement sections + sets refined: true
/modernize-implement S-001  # writes code, runs tests, Step 7 reviewer panel auto-fixes inline,
                            # sets status: done, archives to implemented/, opens ready-for-review PR
/modernize-finalize S-001   # docs-prune pass, squash-merge, branch delete, issue close

# Side path (operator-invoked when implement escalates or post-hoc revisit):
/modernize-rework S-001     # walks scope/design pivots, files follow-up stories if needed

# Then the next story (or use throughput extensions below):
/modernize-refine S-002
# ...

# Throughput extensions (optional, layer on top):
/modernize-refine-ahead 5   # speculatively refine the next 5 unblocked stories
/modernize-fleet 3          # run refine→implement on 3 stories concurrently in worktrees
/modernize-sweep-finalize   # auto-finalize every story that satisfies the gate (no judgment calls)
```

Re-running a planning phase (1–4) regenerates its artifact in place. Re-running `/modernize-refine` on a story replaces its refinement sections atomically. Re-running `/modernize-implement` on a story that's already `done` is refused — explicitly flip status if you want to redo work, or invoke `/modernize-rework` for a scope/design pivot.

## Orchestration extensions

Three skills layer on top of the seven phases to improve throughput without compromising the per-story state-machine guarantees. All are opt-in.

| Skill | Purpose | Typical use |
|---|---|---|
| `/modernize-refine-ahead [N]` | Speculative buffer-fill — refines the next N unblocked stories ahead of when implement needs them. Stamps `refined_speculative: true` so implement can re-refine if stale. | Run before a fleet batch, or on a `/loop` cadence to keep a rolling buffer of refined stories. |
| `/modernize-sweep-finalize` | Daemon-style finalize — scans `stories/implemented/`, auto-finalizes anything that satisfies the gate without judgment calls. Defers ADR amendments, `CHANGES_REQUESTED` PRs, and unclear docs-prune cases. | Wrap in `/loop 30m /modernize-sweep-finalize` or `/schedule` for unattended cadence. |
| `/modernize-fleet [N]` | Parallel-fleet orchestrator — dispatches up to N independent unblocked stories to isolated worktrees and runs refine→implement concurrently (implement's Step 7 reviewer panel runs per worktree). Batches operator checkpoints. | Run on the long tail of leaf stories (CRUDs, observability, scheduled jobs). Foundational stories stay JIT. |

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
4. **After phase 6** — `status: done`, `done_at: <date>`, body pruned to load-bearing decisions, **file moved to `stories/implemented/S-NNN-*.md`** in the mark-done commit. Reviewer findings (maintainability / security / tech-writer / usability / parity) were auto-fixed inline by Step 7's reviewer panel; no `## Review` section is written — the code commits + the PR diff are the evidence trail.
5. **After phase 7** (`merged: true`) — `merged_at` stamped, PR squash-merged to `main`, branch gone, issue closed; the finalize docs-prune pass deleted prose the code now sources more reliably (file trees, method signatures, stale citations after renames). `merge_commit` is not stamped — recoverable via `git log -- docs/modernization/stories/implemented/S-NNN-*.md` if needed.

**Side path — rework.** If `/modernize-rework <S-NNN>` is invoked (operator-only), `reworked: true` + `reworked_at` stamp; follow-up stories filed under `rework_followups: [S-XXX, ...]`. Most stories never see this state.

## Why "generic skills + project seed"

The SKILL.md files in `.claude/skills/` are project-agnostic — they could plan and execute a modernization for any legacy app. The specialist agents in `.claude/agents/` are also general personas, not project-specific (here applied to the FLS → AlpenFlight rewrite). Project context lives in [`00-seed.md`](00-seed.md), which the skills read as their first step. If you adapt this workflow to another project, you replace the seed and (almost) nothing else.
