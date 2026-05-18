---
name: modernize-rework
description: Phase 8 — triage findings from /modernize-review. Per finding: address-now / defer / accept. Step 3.5 surfaces meta-improvements. Trigger: /modernize-rework S-NNN [--bold].
---

# Phase 8 — Story Rework

Take one just-reviewed story and walk every finding in `## Review` to a state: **address-now** / **defer** / **accept**. Does not write code; produces a TaskCreate list for the operator to fix between this skill and the next `/modernize-review`.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md). Doc-drift findings should normally be improvements/nudges; only block when the drift actively misleads.

## Modes

- `/modernize-rework S-NNN` — interactive. Every open finding gets an `AskUserQuestion`.
- `/modernize-rework S-NNN --bold` — auto-triage cheap end; only prompt for blockers + ambiguous improvements.

## Preconditions

1. Single `S-NNN` arg. Story file at top-level `stories/` (refuse if in `implemented/`).
2. `reviewed: true` in frontmatter (else "run /modernize-review first").
3. `review_outcome` ∈ {`blockers`, `improvements-only`}. `pass` → refuse.
4. `## Review` section parseable between `<!-- modernize-review: start --> / end -->` delimiters.

## Procedure

### Step 1 — Parse findings

Read story + each ADR in `adr_refs`. Parse `## Review` into `{dimension, severity, text, path, line, status}`. Skip findings already marked `[accepted: …]` / `[auto-accepted: …]` / `[deferred → S-XXX]`. `[in-rework]` / `[auto-in-rework]` are re-prompted.

### Step 2 — Triage

Per finding (interactive: `AskUserQuestion`; `--bold`: auto-rules below, prompt residue).

`--bold` rules:

| Severity | Heuristic | Auto-decision | Annotation |
|---|---|---|---|
| `blocker` | (any) | **never auto-decide — always prompt** | (operator-driven) |
| `improvement` | single `path:line` + single-sentence `**Fix:**` | `auto-address-now` | `[auto-in-rework]` |
| `improvement` | multi-cite / ambiguous fix / no path | prompt | (operator-driven) |
| `nudge` | (any) | `auto-accept` | `[auto-accepted: <derived rationale>]` |

Nudge rationale: first ≤80-char phrase from why-it-matters; else `auto-accepted via --bold: <severity> severity, no contract impact`.

**Blockers default to address-now; defer/accept requires second confirmation** ("This is a blocker — defer/accept anyway? <yes / re-choose>").

### Step 3 — Process decisions

**address-now / auto-address-now:** TaskCreate `Rework S-NNN: <finding> (path:line)`. Prepend `[in-rework]` or `[auto-in-rework]` to the bullet. Operator does the fix.

**defer:** mint next free `S-NNN` (max ID across `stories/` + `stories/implemented/` + 1). Create `stories/S-NNN-<slug>.md` with the **lean stub format**:

```yaml
id: S-NNN
title: <finding text, ≤ 70 chars>
epic: <originating epic>
status: todo
estimate: <S | M | L>
depends_on: []
origin: rework
origin_story: <S-NNN-originating>
```

Body: 1-2 sentence `## Context` quoting the finding + the path it touches; 1-line `## Acceptance criteria` (testable). Skip empty `parity_test:` / `adr_refs:` / `refined:` keys — `/modernize-refine` adds them when it runs. Append to `_ORDER.md` after the originating row. Prepend `[deferred → S-XXX]` to the `## Review` bullet.

**accept / auto-accept:** prepend `[accepted: <rationale>]` (interactive) or `[auto-accepted: <rationale>]` (`--bold`).

Re-runs replace prior annotations (never stack). A non-bold re-run replaces `auto-*` with operator-chosen; reverse never happens (operator decisions are sticky).

### Step 3.5 — Meta-pass

Scan dispositions for patterns that suggest the workflow / governance should change:

1. **Skill / agent / CI improvement** — ≥ 2 findings of the same shape suggest the workflow should catch it at source.
2. **ADR addition / amendment** — story invented a pattern not covered; or existing ADR silently violated.
3. **`CONVENTIONS.md` update** — pattern emerged that other stories should mirror.

Prompt the operator with top candidates (batched ≤ 4). Each gets 3 options:

- **Apply now (boyscout)** — fold into THIS PR. Draft inline; lands in the same rework commit. Per [[feedback-meta-improvements-are-boyscout]] — **never** spin a separate `chore/*` branch.
- **File a follow-up story** — mint `S-NNN` with `origin: rework-meta` + `kind: workflow-improvement | adr-addition | adr-amendment | conventions-update`.
- **Skip.**

If 0 patterns surface, record `rework_meta_improvements: 0`. Meta-improvements never auto-decide.

### Step 4 — Write back

Replace `## Review` section in place. Frontmatter — only the load-bearing fields. Per-decision counts go into the operator report, not the file (commit history + the annotated `## Review` block carry that).

```yaml
reworked: true
reworked_at: <ISO date>
rework_mode: interactive | bold        # only when --bold (default interactive omitted)
rework_followups: [S-XXX, ...]          # only when non-empty
rework_meta_followups: [{id: S-XXX, kind: ...}, ...]  # only when non-empty
```

Skip a key when its value is the default. Stamping `rework_address_now: 0` is noise.

Commit: `#N: rework triage — <X address-now / Y deferred / Z accepted>` (append ` (--bold)` in bold mode).

**Note on `## Review` lifecycle.** Annotations (`[in-rework]`, `[auto-accepted: …]`, `[deferred → S-XXX]`) stay through rework + the re-review pass. `/modernize-finalize` prunes the section to its load-bearing remnants (deferred references) when the story archives. Don't write annotations expecting them to live forever — they're working notes.

### Step 5 — Report

- Story ID + title + mode.
- Findings triaged + per-decision count.
- Auto-decisions (if `--bold`): count + severity breakdown. Audit prompt: "re-run without --bold to overrule".
- Address-now TaskCreate list (mark `[auto]` for auto-decided).
- Deferred follow-ups (S-NNN + titles).
- Meta-improvements: apply-now changes + follow-up stories, or "none surfaced".
- Next: `address-now > 0` → fix, push, `/modernize-review S-NNN` → `/modernize-finalize`. `address-now == 0` → `/modernize-review` to confirm → `/modernize-finalize`.

## Quality bar

- One story per invocation; one decision per finding.
- Blockers never auto-decide. Defer/accept requires second confirmation.
- Annotations replace, never stack.
- Skill writes no code.
- Meta-improvements **always boyscout** per [[feedback-meta-improvements-are-boyscout]].
- `--bold` is opt-in.
- Doc-drift = improvement/nudge by default per [ADR 0022 directive 1](../../../docs/modernization/adrs/0022-modernization-primary-directives.md); blocker only when drift actively misleads.

## Not in scope

Code edits (operator). PR merging (`/modernize-finalize`). Refinement re-runs. `_ORDER.md` reordering (operator).
