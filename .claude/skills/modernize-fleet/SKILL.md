---
name: modernize-fleet
description: Parallel-fleet orchestrator — dispatches N independent unblocked stories to isolated git worktrees and runs refine→implement concurrently (implement Step 7 runs the reviewer panel per worktree). Trigger: /modernize-fleet [N] (default 3).
---

# Modernize — Fleet

Pick N independent unblocked stories; dispatch each to its own git worktree via a `general-purpose` subagent (`isolation: "worktree"`); run refine→implement concurrently (implement Step 7's reviewer panel + auto-fix loop runs inside each worktree). Batch the operator checkpoint.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md). Fleet is the highest-throughput path; the directives shape what subagents prioritise.

**Wins:** throughput (N stories in ~1× wall-clock), batched operator attention, crash-isolated work units.
**Trade-off:** leaf stories only — foundational changes (shared schema, ADR-load-bearing infra) need JIT sequencing.

## When to use / not

**Use:** long tail of leaf stories (CRUDs, observability, scheduled jobs, small follow-ups); stories from different epics with no shared paths; after `/modernize-refine-ahead N` filled the buffer.

**Don't use:** foundational stories (E-01 / E-02 / E-03); stories that share a file path; stories in a parity-flagged epic where the parity oracle is itself being built; first few stories of an epic (establish pattern sequentially first).

## Preconditions

1. Optional integer `N` (default 3). `N ∈ [2, 5]`.
2. Working tree clean; on `main`.
3. `git worktree` works (bail clearly if sandbox blocks it).
4. `gh auth status` OK + GitHub remote exists.
5. ≥ 2 fleet-eligible stories per the selection rule.

## Story-selection rule

Walk `_ORDER.md` top-to-bottom. Resolve story files at `docs/modernization/stories/S-NNN-*.md` **top-level only** (skip `implemented/`). Resolve `depends_on` via two-step glob.

Fleet-eligible when ALL:

1. File at top-level `stories/`.
2. `status: todo`.
3. All `depends_on` have `merged: true`.
4. NOT `parity_test` references a not-yet-built harness, OR design notes explicitly say "must be sequential."
5. No file-path overlap with any other selected story (scan `## Tasks` + `## Design notes` for `next/server/.../<file>`, `next/web/.../<file>`).
6. Different epic from every other selected story (cross-epic preferred).

Collect up to `N`. Report any gap.

## Procedure

### Step 1 — Select + confirm

Apply selection rule. Surface candidate set:

```
Fleet candidates (in _ORDER.md order, pairwise co-fleetable):
1. S-NNN (epic E-XX) — <title> — paths: next/server/.../A.java
2. S-NNN (epic E-XX) — <title> — paths: next/web/.../B.ts
3. S-NNN (epic E-XX) — <title> — paths: next/server/.../C.java
<K> selected (<N> requested, <N-K> ineligible: <one-line reason per skip>).
Proceed? [Y/n]
```

Single confirmation. Default Y. Exit cleanly on n.

### Step 2 — Allocate

For each selected `S-NNN-i` (i = 0..K-1):
- **Branch:** `story/S-NNN-<slug>` (implement skill's convention).
- **Port offset:** `25500 + (i × 100)`. Pass as `FLEET_PORT_OFFSET=<offset>` in subagent prompt.
- Worktree allocated by Agent tool's `isolation: "worktree"`.

### Step 3 — Spawn K parallel runners

In ONE message with K tool uses, spawn K `Agent` calls. Each:

- `subagent_type: "general-purpose"`
- `isolation: "worktree"`
- `description: "Fleet runner: S-NNN"`
- `prompt`: per template below.

#### Subagent prompt template

```
You are a fleet runner for story <S-NNN>, part of a parallel batch of <K>.
Your worktree is isolated; you have your own checkout off `main`.

## Task

Execute refine → implement for this ONE story end-to-end in your worktree.
You do NOT have the Skill tool. READ each SKILL.md, then perform its work:

1. /c/Users/roman/IdeaProjects/fls/.claude/skills/modernize-refine/SKILL.md
2. /c/Users/roman/IdeaProjects/fls/.claude/skills/modernize-implement/SKILL.md

Skip modernize-refine if `refined: true` and not stale. Implement Step 7's reviewer panel auto-fixes inline — that's the only review pass.

Primary directives (trump everything): /c/Users/roman/IdeaProjects/fls/docs/modernization/adrs/0022-modernization-primary-directives.md

## Story

File: /c/Users/roman/IdeaProjects/fls/docs/modernization/stories/S-NNN-<slug>.md
ADRs: <full paths from adr_refs>
Project conventions: CLAUDE.md + 00-seed.md + 02-vision-and-constraints.md

## Fleet-specific constraints

- **Port offset:** honor `FLEET_PORT_OFFSET=<offset>` when starting any service
  (docker-compose, ng serve, gradle bootRun, Playwright). If a port is
  hardcoded and you can't override without editing a config the operator owns,
  surface in your result + skip integration tests requiring it.
- **Single branch:** `story/S-NNN-<slug>`. Don't switch.
- **No interactive prompts.** The operator is not watching. If implement
  Step 6 says "escalate", instead:
    - Capture question + context + your recommendation.
    - Set `status: in_progress` + `fleet_escalation: <reason>` on frontmatter.
    - Commit + push current progress.
    - Return result blob with `escalated: true` + verbatim question.
- **Implement Step 7 reviewer panel + auto-fix loop:** RUN IT in full. Findings get fixed inline; the loop converges within the 2-round cap or escalates.
- **Don't finalize.** Stop after implement marks done (PR ready-for-review).

## Result blob

Return a single markdown block:

\`\`\`
## Fleet runner result: S-NNN
- **Status:** done | escalated | failed
- **Branch:** story/S-NNN-<slug>
- **Worktree path:** <auto-reported>
- **GitHub issue:** #N + URL (or null)
- **PR:** #M + URL (or null)
- **Refine outcome:** <re-refined / used existing speculative / skipped>
- **Implement outcome:** N commits, list of work-package titles
- **CI outcome:** green / red (which step?) / not run
- **Self-review outcome:** no blockers / N blockers fixed / escalated
- **Review outcome:** pass | improvements-only | blockers (+ counts per dimension)
- **Escalations:** verbatim question + recommendation
- **Port offset honored:** yes | partial: <which skipped> | no: <reason>
- **Followup actions for operator:** one line per pending decision
\`\`\`

End your work after returning this blob.
```

### Step 4 — Collect

Wait for all K Agents. Collect result blobs. Surface failures verbatim with worktree path.

### Step 5 — Batched checkpoint

Surface the batched outcome table:

```
Fleet run complete (K stories, <wall-clock>):

| Story  | Status     | Review outcome      | Escalations |
|--------|------------|---------------------|-------------|
| S-NNN  | done       | improvements-only   | 0           |
| S-NNN  | done       | blockers (2)        | 0           |
| S-NNN  | escalated  | -                   | 1 (parity)  |
```

For each story needing action, one decision prompt per story:
- **Rework interactively** — `/modernize-rework S-NNN`.
- **Rework with --bold** — `/modernize-rework S-NNN --bold`.
- **Defer** — leave PR open.
- **Address escalation** — present question + recommendation + worktree path; operator decides.

This is the ONLY mid-fleet operator session.

### Step 6 — Sweep-finalize

After rework session, invoke `/modernize-sweep-finalize` directly. It finalizes every story with clear blockers + green CI; defers ADR amendments + CHANGES_REQUESTED to interactive `/modernize-finalize`.

### Step 7 — Cleanup

For finalized worktrees: `git worktree prune`. Remote branches deleted by sweep-finalize. For unfinalized worktrees: leave in place; the operator's follow-up `/modernize-implement S-NNN` picks up the same branch.

### Step 8 — Report

- Fleet started / completed (ISO + wall-clock).
- Stories attempted + per-story status.
- Stories merged (per-story + count).
- Stories pending operator action.
- Aggregate diff (commits / files / lines).
- Port-offset issues (which subagents couldn't honor + why).
- Cross-worktree merge conflicts (operator addresses manually).
- Next: another fleet run if eligible stories remain; else `/modernize-refine-ahead` to refill.

## Quality bar

- Pairwise co-fleetable selection (no file-path overlap).
- One subagent per story.
- Single message, K tool uses.
- No interactive prompts inside subagents.
- Finalize serialized (one story at a time onto main).
- Port offsets honored where applicable.
- Foundational stories excluded.
- Operator confirms once (Step 1) + decides per-story once (Step 5).

## Not in scope

Selecting stories with file-path overlap. Fleeting foundational stories. Merging PRs (sweep-finalize). Iteration. Cross-invocation queue. `_ORDER.md` edits. Auto-applying ADR amendments.
