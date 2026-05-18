---
name: modernize-finalize
description: Phase 9 — terminal: process proposed ADR amendments, squash-merge PR, delete branch, verify issue closure, archive story to implemented/. One-shot. Trigger: /modernize-finalize S-NNN.
---

# Phase 9 — Story Finalize

Take one reviewed story whose blockers are clear; merge the PR; archive bookkeeping. One-shot — either succeeds or refuses.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).

## Story ID resolution

The story ID can be passed explicitly (`S-NNN`) or inferred from the current branch when it matches `story/S-NNN-*` (check via `git rev-parse --abbrev-ref HEAD`; pattern `^story/S-(\d{3})(-.*)?$`):

- **Arg + branch match** → proceed with the arg.
- **Arg + branch is `story/S-MMM-*` where `MMM ≠ NNN`** → bail: *"current branch is `story/S-MMM-...` but you passed `S-NNN`; switch branch or correct the arg."*
- **Arg + branch isn't a story branch** → proceed with the arg.
- **No arg + branch matches `story/S-NNN-*`** → use the branch's `S-NNN`.
- **No arg + branch doesn't match** → prompt the operator for the story ID via `AskUserQuestion` (single question).

## Preconditions

1. Story ID resolved per § Story ID resolution above.
2. Story file at `stories/implemented/S-NNN-*.md` — `/modernize-implement` Step 8 archives the story into `implemented/` as part of the mark-done commit, so it's already there by the time finalize runs. If `merged: true` already → refuse ("already finalized").
3. `reviewed: true`.
4. `review_outcome ∈ {pass, improvements-only}`. `blockers` → refuse ("rework first").
5. `## Review` section has no `[blocker]` bullets lacking an `[accepted: …]` annotation.
6. `github_pr: M` and `gh pr view M --json state` returns `OPEN` (not `MERGED`, not `CLOSED`).
7. PR is `READY_FOR_REVIEW`, not `DRAFT`.
8. `gh pr checks M` shows all green (no `failing` / `in_progress`).
9. No human `CHANGES_REQUESTED` review (best-effort; surface + ask if present).
10. Working tree clean.

Fallback (no `github_pr:`): preconditions 6-9 don't apply. Skill becomes bookkeeping-only — stamp `merged: true`, report. No merge / branch delete.

## Procedure

### Step 1 — Surface + open proposed ADR amendments

Search story body + GH issue body for `ADR amendments proposed` markers. For each:

1. Read `docs/modernization/adrs/NNNN-<slug>.md`.
2. Surface proposal to operator as a markdown preview (not edit-yet).
3. Ask: **apply** (stage edit) / **skip** (drop) / **open for manual edit** (print path; wait for operator's "continue").
4. Applied / manually-edited ADRs commit to `main` **after** PR merge (Step 4) — cross-cutting, not story-PR scope.

No amendments → skip silently.

### Step 2 — Confirm merge strategy

Per [[feedback-always-squash-merge]]: squash without asking. Skip the strategy prompt; default `gh pr merge --squash`.

If operator wants override: ask explicitly via single `AskUserQuestion` with options: squash (default) / merge commit / rebase / abort.

### Step 2.5 — Pre-merge merged-stamp + review-prune commit on the PR branch

The story file already lives at `docs/modernization/stories/implemented/S-NNN-*.md` — `/modernize-implement`'s Step 8 mv'd it there in the same commit as `status: done`. Step 2.5 adds the `merged:` stamps **and prunes `## Review` to its load-bearing remnants** in one commit, before the squash-merge fires.

1. `git fetch origin && git checkout story/S-NNN-<slug> && git pull --ff-only`. Bail "PR branch diverged" if pull fails.
2. Verify the file is already at `implemented/` (it should be — `/modernize-implement` Step 8 moved it). If still at top-level `stories/`, the implementer ran the previous (pre-skill-update) flow; do the mv now per the implement-Step-8 trap-guard ordering as a one-off.
3. **Prune `## Review`** (walk the `<!-- modernize-review: start --> / end -->` block):
   - Drop the `**Reviewed:** … **PR:** … **Outcome:** …` metadata line — `gh pr view` + commit history carry it.
   - Drop bullets annotated `[in-rework]` / `[auto-in-rework]` — the fix landed in the diff; code is the evidence.
   - Drop bullets annotated `[accepted: …]` / `[auto-accepted: …]` UNLESS the rationale is load-bearing (rare — e.g. cites a sacred-cow trade-off that future readers must understand). When in doubt, drop.
   - Drop the entire `### Maintainability` / `### Security` / `### Usability` / `### Code quality` heading once it has zero bullets left.
   - **Keep:** `[deferred → S-XXX]` bullets — they're the lineage explanation for the follow-up stories. Keep the `### Parity` `**Oracle:**` line if a parity oracle exists or was explicitly N/A.
   - If the entire `## Review` block collapses to nothing, delete the section + its delimiters too.
4. Update story frontmatter (edit in place at the `implemented/` path):
   ```yaml
   merged: true
   merged_at: <ISO date>
   status: done  # confirm
   ```
   Do **not** stamp `merge_commit:` — SHA isn't known yet; merge SHA recoverable via `git log -- docs/modernization/stories/implemented/S-NNN-*.md` or `gh pr view M --json mergeCommit`.
5. Commit `#N: pre-merge — stamp merged + prune review`. Push.
6. Watch CI on freshened head (`gh run watch --exit-status <latest-run-id>`). Markdown-only diff usually clears in seconds. On red: surface failure + refuse "fix and re-run". Do NOT auto-revert.

If repo allows `gh pr merge --auto`: MAY substitute Step 2.5's watch with `gh pr merge --auto --squash` (queues for green CI). Default flow is explicit watch-then-merge.

### Step 3 — Merge

- Squash (default): `gh pr merge M --squash --delete-branch --subject "S-NNN: <story title>" --body "Closes #N"`.
- Merge commit: `gh pr merge M --merge --delete-branch`.
- Rebase: `gh pr merge M --rebase --delete-branch`.

On non-zero:
- `not mergeable` (conflicts): refuse "resolve on branch, push, re-run".
- `requires review`: refuse "branch protection needs approval".
- Other: surface gh error verbatim, refuse.

Capture merge commit SHA from `gh pr view M --json mergeCommit -q .mergeCommit.oid` for the Step 8 report.

### Step 4 — Commit pending ADR amendments to main

If Step 1 staged edits:

1. `git checkout main && git pull`.
2. Commit `S-NNN: ADR amendments — <ADR IDs touched>` (body = one bullet per ADR).
3. Push.

ADR amendments commit to `main` directly — recognised exception to story-per-branch (governance artifacts, operator already approved). Document in report.

### Step 5 — Verify issue closure + apply labels

1. `gh issue view N --json state` — should be `CLOSED` (auto-close fired on `Closes #N`).
2. If still `OPEN` (rare): `gh issue close N`.
3. Best-effort labels (skip silently if missing): `--add-label status/merged --remove-label status/in-progress,status/done`.
4. Final comment on issue: "Merged in <merge-commit-SHA>. Story file: `docs/modernization/stories/implemented/S-NNN-<slug>.md`."

### Step 6 — Local cleanup

1. `git checkout main && git pull --ff-only`.
2. `git branch -D story/S-NNN-<slug>` (bail rather than delete current branch).
3. `git fetch -p`; verify `git branch -r --list 'origin/story/S-NNN-<slug>'` returns empty.

`_ORDER.md` is **not** edited — it's a planning artifact; downstream stories `depends_on` still reference shipped stories.

### Step 7 — Report

- Story ID + title + **Outcome:** `merged` (or `merged + ADR amendments`, or `merged (fallback — no PR)`).
- PR: `#M` + URL + `MERGED` + merge commit SHA.
- Merge strategy used.
- ADR amendments processed (if any): IDs + applied/skipped/manual per amendment.
- Issue: `#N` + URL + `CLOSED`.
- Branch cleanup confirmed (local deleted; remote pruned).
- Archive: story file at `docs/modernization/stories/implemented/S-NNN-<slug>.md`.
- Follow-up stories from rework: `rework_followups` list. Operator may refine + implement next.
- Next: `/modernize-refine <next-S-id>` from `_ORDER.md`, or follow-ups if any.

## Quality bar

- One story per invocation. One-shot, not iterative.
- Refuse on blockers without `[accepted: …]` annotation.
- Squash by default (per [[feedback-always-squash-merge]]); other strategies are operator overrides.
- ADR amendments are operator-confirmed (never auto-apply).
- ADR amendments commit to main directly (no PR per amendment).
- Verify after every state-changing call (`gh pr view` after merge, `git branch` after delete, `gh issue view` after expected auto-close).
- Local `main` is clean + fast-forwarded; feature branch gone.
- Finalized stories archive to `stories/implemented/`. Mandatory.
- Bookkeeping rides the PR, not a post-merge main commit. Step 2.5 commits + the squash gives ONE commit on main per story (plus optionally one for ADR amendments).
- `merge_commit:` is NOT stamped on frontmatter (recoverable from git log; can't be known pre-merge).
- **Step 2.5 prunes `## Review` to load-bearing remnants** — deferred-to references, parity-oracle line, anything else is working-notes noise once the PR is about to land.
- Per [[feedback-no-shas-in-committed-docs]]: never embed git SHAs in committed docs. Cite by subject / file:line / PR# / story-ID. SHAs OK in ephemera (issue comments, operator report).

## Not in scope

Code edits. Test runs (PR CI is the gate). Rework iteration. Auto-creating stories. `_ORDER.md` reordering. Force-merging past failing CI / branch protection. Deleting story files (Step 2.5 moves them).
