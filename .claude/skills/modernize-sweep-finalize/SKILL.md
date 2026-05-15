---
name: modernize-sweep-finalize
description: Daemon-style sweep — finalizes every story that satisfies the finalize gate without operator interaction. Defers ADR amendments + CHANGES_REQUESTED PRs. Designed for /loop or /schedule. Trigger: /modernize-sweep-finalize.
---

# Modernize — Sweep Finalize (daemon-style)

You are running a **batch sweep** of the finalize gate. Where `/modernize-finalize S-NNN` finalizes one story per invocation with operator confirmation on merge strategy and ADR amendments, this skill scans **every story** in the workflow, identifies the ones that satisfy the finalize gate **without judgment calls**, and finalizes them automatically.

The split point is governance: any story that requires the operator to decide something (ADR amendment, merge strategy override, CHANGES_REQUESTED override) is **deferred** — annotated for operator follow-up but not finalized in this pass. The remainder ships.

This is designed to be invoked from `/loop` or `/schedule` so the destructive-action gate (squash-merge + branch delete + issue close) becomes throughput plumbing instead of operator overhead.

## When to use

- Wrap in `/loop 30m /modernize-sweep-finalize` to keep shipped-but-unmerged stories from piling up.
- Wrap in `/schedule` (e.g. hourly cron) for unattended cadence — the routine self-skips when nothing is ready.
- One-shot at end-of-day to clean up any stories that completed rework while the operator was busy.

## When NOT to use

- As the only path to finalize. Some stories will *always* be deferred by the auto-skip rules (ADR amendments, CHANGES_REQUESTED) — those need the interactive `/modernize-finalize S-NNN`.
- When the operator wants per-story confirmation. Use `/modernize-finalize S-NNN` for that.
- In the middle of a release branch / freeze window. The skill doesn't know about freezes; document it externally or pause the loop.

## Preconditions

1. The argument is empty (no per-story ID — this is a sweep). If the operator passed `S-NNN`, redirect them to `/modernize-finalize S-NNN` and exit.
2. `docs/modernization/stories/` exists and contains parseable story files.
3. `gh auth status` is OK and a GitHub remote is configured. In fallback mode (no `gh`, no remote), the sweep is reduced to local bookkeeping only — log this clearly and proceed (most stories will be skip-ineligible without `gh`, so the sweep usually no-ops in fallback).
4. The working tree is clean. A dirty tree means the operator is mid-edit; the sweep refuses to risk clobbering uncommitted work. Bail with: "Working tree dirty — commit or stash before sweeping."
5. Current branch is `main` (or the configured default). The sweep moves to `main` for each finalize anyway; refusing here surfaces the unexpected starting state.

These are the only blocking conditions. Everything else is per-story and handled by the skip-or-finalize logic below.

## Per-story disposition logic

For each story file under `docs/modernization/stories/S-NNN-*.md` — **top-level only**, not `stories/implemented/` (those are already shipped; nothing for the sweep to do) — parse the frontmatter and classify into exactly one of:

### Finalize (auto)

All of:
- `merged: true` is **not** set (story hasn't been finalized yet).
- `reviewed: true`.
- `review_outcome` is `pass` or `improvements-only`.
- The `## Review` section has **no** `[blocker]` bullets without an `[accepted: ...]` or `[auto-accepted: ...]` annotation.
- `github_pr:` is set and `gh pr view <M> --json state` returns `OPEN`.
- The PR is `READY_FOR_REVIEW` (not `DRAFT`).
- `gh pr checks <M>` reports all green; no `failing`, no `in_progress`.
- No non-bot human reviewer has a `CHANGES_REQUESTED` review on the PR (`gh pr view <M> --json reviews`).
- The story body and the GitHub issue body have **no** `ADR amendments proposed` / `proposed ADR` marker. ADR amendments require operator judgment; defer.
- The PR is mergeable (`gh pr view <M> --json mergeable` returns `MERGEABLE`, not `CONFLICTING` or `UNKNOWN`).

### Defer (skip, log, but report)

Any of:
- `merged: true` — story is already shipped; nothing to do. (Rare at top level — finalize moves these to `implemented/`. If you see `merged: true` at top level, it's an artifact of a partial finalize or a hand-stamped story; skip it silently and surface the path in the report so the operator can move it manually with `git mv`.)
- `reviewed: false` — story hasn't been reviewed yet.
- `review_outcome: blockers` — operator needs to rework.
- `## Review` has an un-accepted `[blocker]` bullet.
- `github_pr:` absent (fallback-mode story) — sweep can't auto-finalize without a PR.
- PR state is `MERGED` (race / inconsistent) — log and stamp `merged: true` locally so the next sweep skips cleanly.
- PR state is `CLOSED` (unmerged) — investigate; flag for operator.
- PR is `DRAFT` — implement isn't complete; skip.
- `gh pr checks` reports anything red or in-progress — wait for CI.
- Human reviewer has `CHANGES_REQUESTED` — needs operator override.
- ADR amendment proposal present — needs operator approval (Step 1 of `/modernize-finalize`).
- PR is `CONFLICTING` — needs rebase / merge resolution.

### Surface as an error (rare)

- Frontmatter is malformed (YAML parse fail). Log the story ID + the parse error, continue.
- `github_pr:` is set but `gh pr view` returns "not found" (PR was deleted out-of-band). Log, continue.
- An assertion expected to be cheap (e.g. `gh pr checks`) times out / 5xx. Log, continue — the next sweep will retry.

## How to sweep

### Step 1 — Enumerate candidates fast

1. `ls docs/modernization/stories/S-*.md` to get the story-file list. **Do not recurse into `stories/implemented/`** — those files are finalized and have nothing to do for the sweep.
2. For each, read **only** the frontmatter (lines 1 to the second `---`) — full body reads come later if the story is eligible. This keeps the sweep cheap when most stories are clearly ineligible.
3. From frontmatter alone, reject any story where `merged: true` or `reviewed: false` or `review_outcome: blockers` — the cheap rejects.
4. Result: a candidate list (typically << 10 even with 100+ stories in the project).

### Step 2 — Verify each candidate against PR + CI state

For each candidate, in parallel (use multiple `gh` calls per candidate via a single Bash command per candidate; each candidate is independent):

```bash
gh pr view <M> --json number,state,mergeable,reviews,isDraft
gh pr checks <M>
```

Compose the disposition (finalize / defer / error) per the table above.

### Step 3 — Finalize the eligible candidates serially

**Serial, not parallel** — each finalize ends with a push to `main` (the merged-state stamp commit), and `main` is a shared resource. Parallel finalizes would race.

For each eligible candidate, run the **automation subset** of `/modernize-finalize S-NNN`:

1. **Skip Step 1 (ADR amendments)** — by sweep design, candidates with ADR proposals are already in the defer bucket.
2. **Skip Step 2 (merge-strategy confirmation)** — sweep defaults to **squash** (the documented default). No prompt.
3. **Step 2.5 — Pre-merge bookkeeping commit on the PR branch:** same as the interactive flow:
   - `git fetch origin && git checkout story/S-NNN-<slug> && git pull --ff-only`. On diverged refs, move this candidate to the defer bucket with reason "branch diverged from origin" and continue.
   - Stamp story frontmatter: `merged: true`, `merged_at: <today>`. Do not stamp `merge_commit:` (recoverable post-merge via `gh pr view`; not needed in frontmatter).
   - `mkdir -p docs/modernization/stories/implemented && git mv docs/modernization/stories/S-NNN-<slug>.md docs/modernization/stories/implemented/S-NNN-<slug>.md`.
   - Commit `#N: archive — stamp merged state + move to implemented/` and push.
   - `gh run watch --exit-status <latest-run-id>` until CI is green. On red CI: move to the defer bucket with reason "CI failed on bookkeeping commit"; continue.
4. **Step 3 — Merge the PR**: `gh pr merge <M> --squash --delete-branch --subject "S-NNN: <title>" --body "Closes #N"`.
   - On `not mergeable` / `requires review` / any other non-zero: move this candidate to the defer bucket with the gh error verbatim. The bookkeeping commit from Step 2.5 stays on the PR branch — the next sweep pass (or interactive finalize) picks up from there.
5. **Step 4 — skipped** (no ADR amendments by definition).
6. **Step 5 — Verify issue closure + apply labels**, same as the interactive flow.
7. **Step 6 — Local cleanup** (`git checkout main && git pull --ff-only`, `git branch -D story/S-NNN-<slug>`, `git fetch -p`).
8. **Step 7 — skipped** — the bookkeeping was done pre-merge (in Step 2.5). The squash commit on `main` already carries the `merged: true` stamp and the `implemented/` path.

Between candidates: re-pull `main` to incorporate the prior candidate's squash commit, then proceed.

### Step 4 — Report

Print to the user:

- **Sweep started:** ISO timestamp.
- **Stories scanned:** count.
- **Finalized this pass:** count + per-story `S-NNN — PR #M — <title>` lines with merge-commit SHAs.
- **Deferred (need operator):** grouped by reason:
  - `ADR amendments pending:` list with story IDs + ADR refs.
  - `CHANGES_REQUESTED:` list with story IDs + reviewer names.
  - `CI failing / in-progress:` list with story IDs.
  - `CONFLICTING:` list with story IDs.
  - `DRAFT (implement not complete):` list with story IDs.
  - `Other (PR closed / not found):` list with story IDs.
- **Errors:** count + per-story error message.
- **Suggested operator actions:** per defer reason, one line of guidance:
  - ADR amendments → `/modernize-finalize <story-id>` to walk the prompts.
  - CHANGES_REQUESTED → resolve with reviewer, then `/modernize-finalize <story-id>` (which will surface the `CHANGES_REQUESTED` confirmation).
  - CONFLICTING → rebase the story branch; sweep will pick up next pass.

## Quality bar

- **No mid-sweep prompts.** The sweep either auto-finalizes a story or defers it. If a candidate would require any operator confirmation (ADR amendment, merge-strategy choice, CHANGES_REQUESTED override), it goes to the defer bucket. The sweep is designed for unattended cadence; mid-sweep prompts defeat the purpose.
- **Squash by default, always.** No merge-commit / rebase escape hatches in sweep mode. If the operator needs a different strategy for a specific story, they finalize it interactively.
- **Serial finalize.** Two sweeps shouldn't run concurrently on the same repo; the skill assumes a single instance per repo at any time. If a `/loop` invocation overlaps, the second will see a dirty tree (mid-stamp commit) and bail at preconditions — acceptable; the next tick picks up.
- **Verify, don't trust.** After each `gh pr merge`, verify via `gh pr view` that state is `MERGED` and the merge commit SHA is recoverable. After local-branch delete, verify the remote is pruned. Same posture as the interactive finalize.
- **Defer is not skip-and-forget.** Every deferred story appears in the report with the reason. The operator's at-a-glance scan of the report is the audit trail.
- **Bookkeeping rides the PR, not main.** The merged-state stamp + archive move are committed on the PR branch (Step 2.5), so the squash carries them. The sweep does not write direct-to-main per story; finalized stories produce exactly one commit on `main` (the squash).
- **No ADR auto-apply.** Even when an ADR amendment proposal is "obviously safe," the sweep defers. ADRs are governance artifacts; the operator owns them. The interactive `/modernize-finalize` is the only path that touches ADRs.
- **Idempotent at the boundary.** Re-running the sweep immediately after a clean run does nothing — the just-finalized stories all have `merged: true` and skip at Step 1.

## What this skill does *not* do

- It does not finalize stories with proposed ADR amendments. Those defer to interactive `/modernize-finalize`.
- It does not override `CHANGES_REQUESTED` reviews. Those defer.
- It does not pick merge strategy. Squash only.
- It does not rebase, resolve conflicts, or fix red CI. Those are operator (or `/modernize-rework`) responsibilities; the sweep waits.
- It does not modify story frontmatter beyond `merged: true` + `merged_at` (set on the PR branch in Step 2.5 — `merge_commit:` is not stamped). It also moves the story file to `stories/implemented/` as part of the same Step 2.5 commit.
- It does not create or modify ADRs, epics, refinements, or reviews.
- It does not run tests. CI is the gate; if CI is green the sweep trusts it.
- It does not push to `main` at all per story. Step 2.5's bookkeeping commit lives on the PR branch and rides the squash. (ADR amendments would be the only direct-to-main path, and the sweep defers any story with ADR amendments to interactive finalize.)
- It does not delete stories or archive them.

## When done

Every story that was unambiguously ready to ship has shipped — merged to `main`, issue closed, branch deleted, frontmatter stamped. Every story that needed operator judgment is surfaced in the report with a specific reason and follow-up action.

If wrapped in `/loop`, the next tick will pick up whatever was deferred-but-resolved in the meantime (rebase landed, CHANGES_REQUESTED addressed, ADR amendment manually finalized via `/modernize-finalize`).
