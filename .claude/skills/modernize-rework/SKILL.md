---
name: modernize-rework
description: Operator-invoked scope / design pivot on one story. Surfaces decisions, files follow-up stories. Step 3 meta-pass catches cross-cutting workflow improvements. Trigger: /modernize-rework S-NNN.
---

# Modernize — Rework

Take one story and pivot its scope or design when the implementer auto-fix loop hit something the operator must adjudicate, or when the operator decides post-hoc that the shipped shape needs to change. Does not write code; produces a TaskCreate list of decisions + optionally new follow-up stories.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).

## When to invoke

- The `/modernize-implement` self-review loop escalated (auto-fix couldn't converge in 2 rounds; reviewer flagged a contract / ADR / sacred-cow conflict).
- Operator decides after merge that a shipped story needs revisiting — boyscout-sized fixes ride the next story per [[feedback-boyscout-rule-over-clean-prs]]; larger pivots get a new story filed here.
- Operator wants to capture a cross-cutting workflow improvement spotted while reading a recent story's diff (Step 3 below).

**Don't invoke** for:
- Reviewer-flagged improvements that the implementer can auto-fix (those land inline at implement time — that's the whole point of the new shape).
- Trivial documentation cleanups (those happen at `/modernize-finalize` Step 2.5).

## Story ID resolution

The story ID can be passed explicitly (`S-NNN`) or inferred from the current branch when it matches `story/S-NNN-*` (check via `git rev-parse --abbrev-ref HEAD`; pattern `^story/S-(\d{3})(-.*)?$`):

- **Arg + branch match** → proceed with the arg.
- **Arg + branch is `story/S-MMM-*` where `MMM ≠ NNN`** → bail.
- **No arg + branch matches** → use the branch's `S-NNN`.
- **No arg + branch doesn't match** → prompt the operator via `AskUserQuestion`.

## Preconditions

1. Story ID resolved per § Story ID resolution above. Story file at top-level `stories/` OR `stories/implemented/`.
2. Working tree clean.

## Procedure

### Step 1 — Surface the trigger

Single `AskUserQuestion` to capture **what's prompting this rework**:

- An implementer-escalation that just happened? → operator paste the escalation context. Skill spawns a one-shot `solution-architect` consult on it.
- A post-hoc realization on a shipped story? → operator describe the pivot in their own words.
- A meta / workflow pattern they noticed? → skip to Step 3.

The operator's framing drives Step 2's decision set.

### Step 2 — Walk decisions

Each pivot has 1–3 decision points (scope cut, design swap, AC change). Per decision:

- `AskUserQuestion` with the concrete options + recommendation.
- Record disposition: **fold-into-current-PR** (boyscout the open PR if one exists) / **file-follow-up-story** (new `S-NNN`) / **accept-as-is** (rework cancelled, captured as a memory or `## Notes` entry).

When operator picks **file-follow-up-story**:

Mint next free `S-NNN` (max ID across `stories/` + `stories/implemented/` + 1). Create `stories/S-NNN-<slug>.md` with the lean stub format:

```yaml
id: S-NNN
title: <decision text, ≤ 70 chars>
epic: <originating epic>
status: todo
estimate: <S | M | L>
depends_on: []
origin: rework
origin_story: <S-NNN-originating>
```

Body: 1–2 sentence `## Context` quoting the pivot reason; 1-line `## Acceptance criteria` (testable). Skip empty `parity_test:` / `adr_refs:` / `refined:` keys — `/modernize-refine` adds them. Append to `_ORDER.md` after the originating row.

### Step 3 — Meta-pass

Scan the operator's framing for patterns that suggest the workflow / governance should change:

1. **Skill / agent / CI improvement** — a recurring failure mode the auto-fix loop misses.
2. **ADR addition / amendment** — story invented a pattern not covered, or existing ADR silently violated.
3. **`CONVENTIONS.md` update** — pattern emerged that other stories should mirror.

Prompt the operator with top candidates (batched ≤ 4). Each gets 3 options:

- **Apply now (boyscout)** — fold into the originating story's PR if still open, else into the next PR per [[pending-boyscout-followups]]. Per [[feedback-meta-improvements-are-boyscout]] — **never** spin a separate `chore/*` branch.
- **File a follow-up story** — mint `S-NNN` with `origin: rework-meta` + `kind: workflow-improvement | adr-addition | adr-amendment | conventions-update`.
- **Skip.**

Operator may invoke `/modernize-rework S-NNN` solely for this step when they spot a meta-pattern (no story-level pivot in mind).

#### Apply-now propagation check (mandatory)

When **Apply now (boyscout)** is chosen for an ADR amendment, conventions update, or any cross-cutting identifier / URL / config-key change, run the propagation check IN THE SAME COMMIT:

1. **Grep for downstream references** across `docs/`, `alpenflight/`, `e2e/`, `CLAUDE.md` (use the most specific identifier — not a substring catching unrelated text).
2. **Update load-bearing references** (story task lines, cross-doc cites, CONVENTIONS examples) in the same commit.
3. **Retract the originator-story TODO section** if applicable — leaving "operator's call: amend now or batch later" as a contradiction inside the file.
4. **Skip historical refs** (per ADR 0022 directive 1) — implemented stories whose snippets are point-in-time fall under "annotate as superseded" not "rewrite the body."

Record the propagation-grep result one-liner in the commit body.

### Step 4 — Write back

Frontmatter (only load-bearing fields):

```yaml
reworked: true
reworked_at: <ISO date>
rework_followups: [S-XXX, ...]              # only when non-empty
rework_meta_followups: [{id: S-XXX, kind: ...}, ...]  # only when non-empty
```

Skip a key when its value is the default.

Commit: `#N: rework — <one-line summary of the pivot>`.

### Step 5 — Report

- Story ID + title.
- Trigger (one sentence).
- Decisions: per-decision disposition + follow-up IDs.
- Meta-pass: apply-now changes + follow-up stories, or "none surfaced".
- Next: if follow-up stories filed → `/modernize-refine <next-S-id>` for each. If fold-into-current-PR → operator applies the change to the open PR.

## Quality bar

- One story per invocation; one decision per `AskUserQuestion`.
- Skill writes no code (operator implements the pivot afterwards).
- Meta-improvements **always boyscout** per [[feedback-meta-improvements-are-boyscout]].
- Doc-drift defaults to improvement / nudge per [ADR 0022 directive 1](../../../docs/modernization/adrs/0022-modernization-primary-directives.md); this skill is for scope / design pivots, not doc cleanups (those land at `/modernize-finalize`).

## Not in scope

Code edits (operator). PR merging (`/modernize-finalize`). Refinement re-runs. `_ORDER.md` reordering beyond the appended-follow-up rows. Routine review-finding triage — the implementer auto-fixes reviewer findings inline; only escalated scope/design pivots come here.
