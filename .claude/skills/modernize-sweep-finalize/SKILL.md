---
name: modernize-sweep-finalize
description: Daemon-style sweep — finalizes every story that satisfies the finalize gate without operator interaction. Defers ADR amendments + CHANGES_REQUESTED PRs. Designed for /loop or /schedule. Trigger: /modernize-sweep-finalize.
---

# Modernize — Sweep Finalize

Batch the finalize gate. Scan every top-level story; auto-finalize the ones that pass the gate **without judgment calls**; defer the rest with a reason.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).

## When to use / not

**Use:** wrap in `/loop 30m` or `/schedule` for unattended cadence; one-shot at end-of-day cleanup.

**Don't use:** as the only finalize path (ADR amendments + CHANGES_REQUESTED always defer); when operator wants per-story confirmation; during release freezes.

## Preconditions

1. No per-story arg (sweep, not single). If operator passed `S-NNN`, redirect to `/modernize-finalize S-NNN`.
2. `docs/modernization/stories/` exists with parseable story files.
3. `gh auth status` OK + GitHub remote. Fallback (no `gh`): local bookkeeping only; log + proceed (most stories skip).
4. Working tree clean. Bail if dirty.
5. On `main`.

## Per-story disposition

For each story at `docs/modernization/stories/S-NNN-*.md` **top-level only** (skip `implemented/`).

### Finalize (auto)

ALL of:
- `merged: true` is **not** set.
- `reviewed: true`.
- `review_outcome ∈ {pass, improvements-only}`.
- `## Review` has no un-`[accepted: …]` `[blocker]` bullets.
- `github_pr:` set; `gh pr view <M> --json state` returns `OPEN`.
- PR is `READY_FOR_REVIEW`, not `DRAFT`.
- `gh pr checks <M>` all green.
- No human `CHANGES_REQUESTED` review.
- No `ADR amendments proposed` marker in story body OR GH issue body.
- `gh pr view <M> --json mergeable` returns `MERGEABLE`.

### Defer (skip + log)

Any of:
- `merged: true` at top-level (rare; partial-finalize artifact — log path so operator can `git mv`).
- `reviewed: false`.
- `review_outcome: blockers`.
- Un-`[accepted]` `[blocker]` in `## Review`.
- `github_pr:` absent (fallback story).
- PR `MERGED` (race; stamp `merged: true` locally so next sweep skips).
- PR `CLOSED` unmerged; investigate.
- PR `DRAFT`.
- CI red / in-progress.
- `CHANGES_REQUESTED` from a human reviewer.
- ADR amendment proposal present.
- `CONFLICTING`.

### Surface as error (rare)

- Malformed YAML frontmatter (log + continue).
- `github_pr:` points to deleted PR (log + continue).
- `gh` 5xx / timeout (log + continue; next sweep retries).

## Procedure

### Step 1 — Enumerate candidates

1. `ls docs/modernization/stories/S-*.md` (skip `implemented/`).
2. Read only frontmatter (to second `---`) for each.
3. Reject cheap: `merged: true`, `reviewed: false`, `review_outcome: blockers`.
4. Result: candidate list (typically << 10 even with 100+ stories).

### Step 2 — Verify each candidate vs PR + CI state

Per candidate, in parallel:

```bash
gh pr view <M> --json number,state,mergeable,reviews,isDraft
gh pr checks <M>
```

Compose disposition per the table.

### Step 3 — Finalize serially

Serial, NOT parallel — each finalize pushes the bookkeeping commit + triggers a merge; `main` is shared.

Per eligible candidate, run the automation subset of `/modernize-finalize`:

1. **Skip Step 1** (ADR amendments) — eliminated by defer rule.
2. **Skip Step 2** (merge-strategy prompt) — sweep defaults to **squash** (no prompt).
3. **Step 2.5 — pre-merge bookkeeping commit on PR branch:**
   - `git fetch origin && git checkout story/S-NNN-<slug> && git pull --ff-only`. Diverged → defer with reason "branch diverged".
   - Stamp `merged: true`, `merged_at: <today>` on frontmatter. Don't stamp `merge_commit:`.
   - `mkdir -p docs/modernization/stories/implemented && git mv …`.
   - Commit `#N: archive — stamp merged state + move to implemented/`. Push.
   - `gh run watch --exit-status <latest-run-id>`. Red → defer with reason "CI failed on bookkeeping commit".
4. **Step 3 — merge:** `gh pr merge <M> --squash --delete-branch --subject "S-NNN: <title>" --body "Closes #N"`.
   - Non-zero (`not mergeable` / `requires review` / other): defer with verbatim gh error. The bookkeeping commit stays on the PR branch.
5. **Step 4 — skipped** (no ADR amendments).
6. **Step 5 — verify issue closure + apply labels.**
7. **Step 6 — local cleanup** (`git checkout main && git pull --ff-only`, `git branch -D story/S-NNN-<slug>`, `git fetch -p`).
8. **Step 7 — skipped** (bookkeeping rode the PR in Step 2.5).

Between candidates: re-pull `main` to incorporate the prior squash, then proceed.

### Step 4 — Report

- **Sweep started:** ISO timestamp.
- **Stories scanned:** count.
- **Finalized this pass:** count + per-story `S-NNN — PR #M — <title>` + merge commit SHAs.
- **Deferred (need operator)**, grouped by reason:
  - `ADR amendments pending:` IDs + ADR refs.
  - `CHANGES_REQUESTED:` IDs + reviewer names.
  - `CI failing / in-progress:` IDs.
  - `CONFLICTING:` IDs.
  - `DRAFT (implement not complete):` IDs.
  - `Other (PR closed / not found):` IDs.
- **Errors:** count + per-story message.
- **Suggested operator actions** per defer reason:
  - ADR amendments → `/modernize-finalize <id>` to walk prompts.
  - CHANGES_REQUESTED → resolve with reviewer, then `/modernize-finalize <id>`.
  - CONFLICTING → rebase story branch; sweep picks up next pass.

## Quality bar

- **No mid-sweep prompts.** Auto-finalize OR defer; never ask.
- **Squash by default, always.** No strategy override in sweep mode.
- **Serial finalize.** Single instance per repo. Overlap → second one bails at preconditions.
- **Verify after each state change** (`gh pr view`, `git branch`, `gh issue view`).
- **Defer ≠ skip-and-forget.** Every deferred story in the report with reason.
- **Bookkeeping rides the PR** (Step 2.5 on PR branch + squash). Sweep does NOT push direct-to-main per story.
- **No ADR auto-apply.** Even "obviously safe" defers.
- **Idempotent at the boundary.** Re-running immediately does nothing.

## Not in scope

ADR amendments. `CHANGES_REQUESTED` override. Merge-strategy choice. Rebase / conflict resolution. Red-CI fixes. Frontmatter beyond `merged: true` + `merged_at`. ADRs / epics / refinements / reviews. Test runs (CI is the gate). Per-story direct-to-main push.
