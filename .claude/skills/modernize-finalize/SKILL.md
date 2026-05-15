---
name: modernize-finalize
description: Phase 9 — terminal: surface + open proposed ADR amendments, squash-merge PR, delete branch (local + remote), verify issue closure, stamp merged-state on the story. One-shot. Trigger: /modernize-finalize S-NNN.
---

# Phase 9 — Story Finalize (merge + cleanup)

You are running phase 9 of the modernization workflow — the terminal phase. Your job is to take **one** reviewed story (S-NNN) whose blockers are clear and ship it: process any proposed ADR amendments, merge the PR cleanly, delete the branch, and bookkeep the result.

This is a one-shot terminal skill. Unlike `/modernize-rework`, finalize does not iterate. It runs once at the end of a story's lifecycle and either succeeds (story merged + bookkept) or refuses (preconditions not met).

## Preconditions

1. The argument is a single story ID `S-NNN`. If missing, ask.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md`.
3. The story has `reviewed: true`.
4. The story's `review_outcome` is `pass` or `improvements-only`. If `blockers`, refuse with: "Review still has blockers. Resolve via `/modernize-rework S-NNN` → fix → `/modernize-review S-NNN` → finalize."
5. The story's `## Review` section has no `[blocker]` bullets without an `[accepted: ...]` annotation (sweep the section to verify — `review_outcome` alone could be stale if a re-review hasn't been run).
6. The story has `github_pr: M` and `gh pr view M --json state` returns `OPEN`. If `MERGED`, refuse with: "PR already merged. Run the post-merge cleanup manually if state is inconsistent." If `CLOSED` (unmerged), refuse with: "PR closed without merge — investigate."
7. The PR is `READY_FOR_REVIEW`, not `DRAFT`. If draft, refuse with: "PR is still draft. Run `/modernize-implement S-NNN` to complete the work first, or flip ready-for-review manually."
8. CI is green on the latest commit of the PR's head ref. Check `gh pr checks M`. If any check is `failing` or `in_progress`, refuse with the specific check name + status.
9. No human reviewer has requested changes (`gh pr view M --json reviews` — best-effort; if any `state: CHANGES_REQUESTED` from a non-bot author is present, surface it and ask whether to proceed anyway).
10. The working tree is clean. Uncommitted state would leak across the merge.

In fallback mode (no `github_pr:`, commit-only story): preconditions 6–9 don't apply. Skill becomes a bookkeeping-only pass — no merge, no branch delete, just stamp `merged: true` (interpreted as "shipped via direct push") and report.

## How to finalize

### Step 1 — Surface and open proposed ADR amendments

The `/modernize-implement` done report may have proposed ADR amendments (recorded in the story file's "## Implementation report" or similar archive, or in the issue body). Extract them:

1. Search the story file body and the GitHub issue body for `ADR amendments proposed` or `proposed ADR` markers.
2. For each proposed amendment, gather: ADR ID, what shifted, recommended change (verbatim from the implement done report).

For each proposed amendment, **open the ADR file for operator edit**:

1. Read `docs/modernization/adrs/NNNN-<slug>.md`.
2. Surface the proposal to the operator in a markdown block (preview, not edit-yet).
3. Ask: "Apply this amendment / skip / open for manual edit?"
   - **Apply**: write the suggested change to the ADR (operator confirmed). Stage but don't commit yet — the operator may want to review the diff first.
   - **Skip**: drop the proposal; note `(skipped at finalize)` in the report.
   - **Open for manual edit**: print the ADR path; operator edits in their editor; resume finalize when they're done. (The skill doesn't watch the editor — it just waits for the operator to say "continue".)
4. If any ADR edits were applied or made manually, commit them on `main` **after** the PR merge in Step 3 — they're cross-cutting and shouldn't go through the story's PR.

If no proposed amendments exist: skip Step 1 silently.

### Step 2 — Confirm merge

Single `AskUserQuestion`:

- "Merge PR #M (squash, delete branch) for story S-NNN?"
  - **Merge (squash)** — default; one commit on `main`, branch deleted.
  - **Merge (merge commit)** — operator override; preserves every work-package commit.
  - **Merge (rebase)** — linear history.
  - **Abort** — don't merge; report state and exit.

Squash is the documented default per the modernize-* workflow design. Other choices are escape hatches.

### Step 3 — Merge the PR

Based on the operator's choice:

- Squash: `gh pr merge M --squash --delete-branch --subject "S-NNN: <story title>" --body "Closes #N"`.
- Merge commit: `gh pr merge M --merge --delete-branch`.
- Rebase: `gh pr merge M --rebase --delete-branch`.

The `--delete-branch` flag deletes the remote feature branch. The skill explicitly handles the local branch separately in Step 5 (safer than letting `gh` shell out).

Wait for the merge to complete (`gh pr merge` is synchronous when given a state that's mergeable). If `gh pr merge` returns non-zero:

- `not mergeable` (conflicts with main): refuse with "PR has conflicts with main. Resolve them on the story branch, push, then re-run finalize."
- `requires review` (branch protection): refuse with "Branch protection requires a human reviewer's approval. Get the approval, then re-run finalize."
- Any other error: surface the gh error verbatim and refuse.

Capture the merge commit SHA from `gh pr view M --json mergeCommit -q .mergeCommit.oid`.

### Step 4 — Commit any pending ADR amendments to main

If Step 1 applied or staged ADR edits:

1. `git checkout main && git pull` to sync.
2. Commit the ADR edits with message: `S-NNN: ADR amendments — <list of ADR IDs touched>`. Body summarizes the changes (one bullet per ADR).
3. Push.

ADR amendments do not go through their own PR — they're stamped on `main` directly. This is a recognized exception to the story-per-branch rule: ADRs are governance artifacts, the operator already approved the amendments in Step 1, and a separate PR per amendment would be ceremony without benefit. Document the exception in the skill's report.

### Step 5 — Verify issue closure + apply labels

1. `gh issue view N --json state` — should be `CLOSED` (auto-close fired on `Closes #N` keyword in the squash-merge commit message).
2. If still `OPEN` (rare — e.g. `Closes` keyword was malformed): close manually with `gh issue close N`.
3. Apply labels (best-effort, skip silently if missing): `--add-label status/merged --remove-label status/in-progress` and any `status/done` label that was applied at review time.
4. Post a final comment on the issue: "Merged in <merge-commit-SHA>. Story file: `docs/modernization/stories/S-NNN-<slug>.md`."

### Step 6 — Local cleanup

1. `git checkout main && git pull --ff-only` — sync local main with the freshly-merged commit.
2. `git branch -D story/S-NNN-<slug>` — delete the local feature branch. If it's the current branch (shouldn't be after checkout main, but safeguard), bail rather than delete the current branch.
3. Verify the remote branch is gone: `git fetch -p` to prune; `git branch -r --list 'origin/story/S-NNN-<slug>'` should return empty.

### Step 7 — Stamp story frontmatter

Update the story file's frontmatter:

```yaml
merged: true
merged_at: <ISO date>
merge_commit: <SHA from Step 3>
status: done  # confirm; should already be done from implement
```

Commit this single-file change to `main` with message `S-NNN: stamp merged state` and push. This commit lives on `main`, not on a branch — it's the final breadcrumb that the story has shipped.

If ADR amendments also landed in Step 4, the frontmatter stamp can be folded into the same commit (operator preference; default is separate commits for log clarity).

### Step 8 — Report

Print to the user:

- Story ID + title.
- **Outcome:** `merged` (or `merged + ADR amendments` if Step 4 ran, or `merged (fallback mode — no PR)` if commit-only).
- **PR:** `#M` + URL + `MERGED` state + merge commit SHA.
- **Merge strategy used:** squash / merge / rebase.
- **ADR amendments processed (if any):** ADR IDs + applied/skipped/manual-edit per amendment.
- **Issue:** `#N` + URL + `CLOSED` state.
- **Branch cleanup:** local `story/S-NNN-<slug>` deleted, remote pruned.
- **Follow-up stories from rework (if any):** the rework frontmatter's `rework_followups` list. Operator may want to refine + implement these next.
- **Suggested next action:** `/modernize-refine <next-S-id>` from `_ORDER.md`. If rework follow-ups exist, surface those as candidates.

## Quality bar

- **One story per invocation.** Batching is forbidden.
- **One-shot, not iterative.** Finalize either succeeds or refuses. No "let's retry the merge with a different config" loop.
- **Refuse on blockers.** A story with open `[blocker]` bullets in `## Review` doesn't get finalized. The operator runs `/modernize-rework` → fix → `/modernize-review` → re-finalize.
- **Squash by default.** Single commit on main per story keeps `git log main` scannable. Other strategies are operator overrides, used sparingly.
- **ADR amendments are operator-confirmed.** Never auto-apply. Surface, ask, then apply only on explicit confirmation.
- **ADR amendments commit to main directly.** No PR for governance-doc edits the operator already approved. Document the exception in the report.
- **Verify, don't trust.** After every state-changing call (`gh pr merge`, branch delete, issue close), verify with a read-side call (`gh pr view`, `git branch`, `gh issue view`). The skill's report is only as honest as its verification.
- **Local main stays clean.** After Step 6, the operator is on `main`, fast-forwarded, with the feature branch gone. Next story starts from a clean slate.

## What this skill does *not* do

- It does not modify application code. The story's code is already merged; the only mutations are bookkeeping (story frontmatter, ADR edits the operator approved).
- It does not run tests. The PR's CI is the gate; if green, finalize proceeds. If red, finalize refuses (precondition 8).
- It does not iterate the rework cycle. That's `/modernize-rework`.
- It does not auto-create stories. Follow-up stories were created by `/modernize-rework`; finalize only surfaces them.
- It does not reorder `_ORDER.md`. Order is set at decompose / rework time.
- It does not force-merge past failing CI or branch protection. Those are real gates; the operator addresses them outside this skill.
- It does not delete the story file or archive it. Story files are permanent record; `merged: true` + `merge_commit:` is the signal that the story has shipped.
- It does not push to `main` outside of Steps 4 and 7. Those two pushes (ADR amendments + frontmatter stamp) are the only direct-to-main writes; the PR merge itself is GitHub-side.

## When done

The story is merged to `main`, the tracking issue is closed, the feature branch is gone (local + remote), the story file's frontmatter reflects the merged state, any ADR amendments are committed to main, and the operator has a clear next action (the next story).

The story is **shipped**. Subsequent stories that `depends_on: [S-NNN]` will pass the `/modernize-implement` precondition that the dependency's PR is merged into main.
