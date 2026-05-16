---
name: modernize-finalize
description: Phase 9 — terminal: surface + open proposed ADR amendments, squash-merge PR, delete branch (local + remote), verify issue closure, stamp merged-state on the story. One-shot. Trigger: /modernize-finalize S-NNN.
---

# Phase 9 — Story Finalize (merge + cleanup)

You are running phase 9 of the modernization workflow — the terminal phase. Your job is to take **one** reviewed story (S-NNN) whose blockers are clear and ship it: process any proposed ADR amendments, merge the PR cleanly, delete the branch, and bookkeep the result.

This is a one-shot terminal skill. Unlike `/modernize-rework`, finalize does not iterate. It runs once at the end of a story's lifecycle and either succeeds (story merged + bookkept) or refuses (preconditions not met).

## Preconditions

1. The argument is a single story ID `S-NNN`. If missing, ask.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md` (top-level — pending stories). If the file is found instead at `docs/modernization/stories/implemented/S-NNN-*.md`, refuse with: "Story S-NNN is already finalized (in stories/implemented/). Nothing to do." This is the post-finalize resting state — moving it back to top-level is a manual operator decision (e.g. genuine re-opening), not a skill responsibility.
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

### Step 2.5 — Pre-merge bookkeeping commit on the PR branch

The merged-state stamp + the move to `stories/implemented/` are committed **on the PR branch before the merge**, so the squash merge commit on `main` carries them. This eliminates the post-merge direct-to-main commit (formerly Step 7) — the merge IS the bookkeeping.

Rationale: a post-merge `S-NNN: stamp merged state` commit on `main` is a separate push, separate CI run, and separate audit-log entry. Folding it into the PR makes each finalized story produce exactly one commit on `main` (the squash) plus optionally one for ADR amendments (Step 4).

Steps:

1. **Switch to the PR branch:** `git fetch origin && git checkout story/S-NNN-<slug> && git pull --ff-only`. The working tree must be clean (precondition 10); if `pull` reports diverged refs, bail with "PR branch has diverged from origin — investigate before finalizing."
2. **Update story frontmatter** (still at `docs/modernization/stories/S-NNN-<slug>.md` — top-level on the PR branch):
   ```yaml
   merged: true
   merged_at: <today's ISO date>
   status: done   # confirm; should already be done from implement
   ```
   Do **not** stamp `merge_commit:`. The SHA isn't known until after the merge, and a post-merge commit just to add it would defeat the point of this step. The merge SHA is recoverable from `git log -1 --format=%H -- docs/modernization/stories/implemented/S-NNN-<slug>.md` after the move, or from `gh pr view M --json mergeCommit -q .mergeCommit.oid` immediately after the merge (used for the report at Step 8 only).
3. **Move the story file:**
   - `mkdir -p docs/modernization/stories/implemented` (no-op if already present).
   - `git mv docs/modernization/stories/S-NNN-<slug>.md docs/modernization/stories/implemented/S-NNN-<slug>.md`. `git mv` preserves history and stages the rename.
4. **Commit + push:** `#N: archive — stamp merged state + move to implemented/` (or `S-NNN: archive — ...` in GitHub-fallback mode). Then `git push origin story/S-NNN-<slug>`.
5. **Wait for CI green on the freshened head.** The push triggers a fresh CI run; branch-protection rules will block the upcoming merge if CI isn't green. Use `gh run watch --exit-status <latest-run-id>` (the run id comes from `gh run list --branch story/S-NNN-<slug> --limit 1 --json databaseId -q '.[0].databaseId'`). On red CI:
   - Surface the failure to the operator + the run URL.
   - Refuse with "CI failed on the bookkeeping commit — likely a markdown-lint rule on the frontmatter or path move. Investigate; once fixed, re-run finalize."
   - Do **not** auto-revert. The bookkeeping commit is recoverable (a re-run with a fix is straightforward), and reverting risks racing against the operator's investigation.

**Why this layout works:** the markdown-only diff (frontmatter stamp + file rename) typically clears CI in seconds (no Java/TS compile, no test run beyond markdown lint). The wall-clock added vs. the old post-merge stamp is on the order of one CI cycle — and that cycle was happening *anyway* if the operator hand-stamped post-merge.

If precondition 6 noted the repo allows `gh pr merge --auto`, the skill MAY substitute Step 2.5's manual `gh run watch` with `gh pr merge --auto --squash` (queues the merge for when CI passes). This is an optimization; the default flow uses the explicit watch-then-merge pattern for predictability.

### Step 3 — Merge the PR

Based on the operator's choice:

- Squash: `gh pr merge M --squash --delete-branch --subject "S-NNN: <story title>" --body "Closes #N"`.
- Merge commit: `gh pr merge M --merge --delete-branch`.
- Rebase: `gh pr merge M --rebase --delete-branch`.

The `--delete-branch` flag deletes the remote feature branch. The skill explicitly handles the local branch separately in Step 6 (safer than letting `gh` shell out).

Wait for the merge to complete (`gh pr merge` is synchronous when given a state that's mergeable). If `gh pr merge` returns non-zero:

- `not mergeable` (conflicts with main): refuse with "PR has conflicts with main. Resolve them on the story branch, push, then re-run finalize." The bookkeeping commit from Step 2.5 sits on the branch; the operator's conflict resolution proceeds from there.
- `requires review` (branch protection): refuse with "Branch protection requires a human reviewer's approval. Get the approval, then re-run finalize."
- Any other error: surface the gh error verbatim and refuse.

Capture the merge commit SHA from `gh pr view M --json mergeCommit -q .mergeCommit.oid` — used in the Step 8 report only; not stamped on the frontmatter (the file is already in `implemented/` with `merged: true`, and `git log` is the authoritative source for the SHA).

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
4. Post a final comment on the issue: "Merged in <merge-commit-SHA>. Story file: `docs/modernization/stories/implemented/S-NNN-<slug>.md`."

### Step 6 — Local cleanup

1. `git checkout main && git pull --ff-only` — sync local main with the freshly-merged commit.
2. `git branch -D story/S-NNN-<slug>` — delete the local feature branch. If it's the current branch (shouldn't be after checkout main, but safeguard), bail rather than delete the current branch.
3. Verify the remote branch is gone: `git fetch -p` to prune; `git branch -r --list 'origin/story/S-NNN-<slug>'` should return empty.

### Step 7 — *(removed)*

The merged-state frontmatter stamp + the move to `stories/implemented/` happen in **Step 2.5 before the merge**, so the squash commit carries them. No post-merge direct-to-main commit is needed for bookkeeping (ADR amendments in Step 4 remain the only legitimate direct-to-main writes).

`_ORDER.md` is **not** edited at any step. It is a planning artifact and references implemented stories as predecessors of unfinalized ones; mutating it would invalidate dependency context for downstream stories. The presence of `merged: true` + the `implemented/` path is the signal that the story has shipped.

### Step 8 — Report

Print to the user:

- Story ID + title.
- **Outcome:** `merged` (or `merged + ADR amendments` if Step 4 ran, or `merged (fallback mode — no PR)` if commit-only).
- **PR:** `#M` + URL + `MERGED` state + merge commit SHA.
- **Merge strategy used:** squash / merge / rebase.
- **ADR amendments processed (if any):** ADR IDs + applied/skipped/manual-edit per amendment.
- **Issue:** `#N` + URL + `CLOSED` state.
- **Branch cleanup:** local `story/S-NNN-<slug>` deleted, remote pruned.
- **Archive:** story file moved to `docs/modernization/stories/implemented/S-NNN-<slug>.md` (Step 7.5).
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
- **Finalized stories archive to `stories/implemented/`.** Step 2.5's `git mv` is mandatory — it's how selection skills (refine-ahead, fleet, sweep-finalize) know the story is no longer a work candidate. Skipping the move leaks finalized stories back into refinement / implementation passes.
- **Bookkeeping rides the PR, not main.** The merged-state stamp + the archive move are committed on the PR branch (Step 2.5), so the squash commit carries them. No post-merge direct-to-main commit is needed for per-story bookkeeping — keeps `main`'s log to one commit per story.
- **`merge_commit:` is not stamped on the frontmatter.** It can't be known at pre-merge time, and a post-merge stamp commit would defeat Step 2.5's purpose. The merge SHA is recoverable from `git log -- docs/modernization/stories/implemented/S-NNN-*.md` (and is reported at Step 8).
- **Never embed git commit SHAs in committed docs.** Story files (incl. `## Review` body), READMEs, ADRs, CONVENTIONS.md, any text that ships in a commit — no `c1ef2f7`-style refs. The reference is broken by construction + squash-merge erases branch SHAs from `main` entirely. Cite by **commit subject**, **file:line**, **PR number** (`#N`), or **story ID** (`S-NNN`). SHAs are fine in ephemera (issue comments per Step 5's "Merged in <merge-commit-SHA>" template, PR descriptions, the operator-facing report) — the merge SHA lives on `main` and persists, branch SHAs don't.

## Pre-merge bookkeeping ordering — guard against the git-mv trap

Step 2.5 edits the story file (frontmatter stamps + `## Review`) and then runs `git mv` to move it from `stories/` to `stories/implemented/`. Git's rename detection can collapse the rename with content changes into "100% similar rename" — and `git commit -a` after a `git mv` sometimes captures the rename without the pre-mv content edits (they get tracked as post-mv unstaged modifications).

**Mandatory ordering inside Step 2.5:**

1. Edit the story file at the original path (stamps + `## Review` section).
2. `git mv` the file to `stories/implemented/`.
3. **`git add <new-path>`** explicitly to stage post-mv content (the rename is already staged but post-rename edits aren't auto-included).
4. **`git diff --cached --stat`** to verify the staged diff shows BOTH the rename and the additions. If the stat reads "0 insertions, 0 deletions" with rename-detection on, the trap fired — re-stage and re-check.
5. Commit + push only after the staged diff confirms both pieces.

**Alternative cleaner ordering:** edit + commit first (one branch commit), then `git mv` + commit (another branch commit). Two branch commits, one squash commit on main. Use this if the trap recurs.

## What this skill does *not* do

- It does not modify application code. The story's code is already merged; the only mutations are bookkeeping (story frontmatter, ADR edits the operator approved).
- It does not run tests. The PR's CI is the gate; if green, finalize proceeds. If red, finalize refuses (precondition 8).
- It does not iterate the rework cycle. That's `/modernize-rework`.
- It does not auto-create stories. Follow-up stories were created by `/modernize-rework`; finalize only surfaces them.
- It does not reorder `_ORDER.md`. Order is set at decompose / rework time. The story ID remains in `_ORDER.md` even after the file moves to `implemented/` — downstream stories' `depends_on` still references it, and the order is the planning record of how the work was executed.
- It does not force-merge past failing CI or branch protection. Those are real gates; the operator addresses them outside this skill.
- It does not delete the story file. Step 2.5 **moves** it into `stories/implemented/`; the content is preserved verbatim. Story files are permanent record; `merged: true` + the `implemented/` path are the signals that the story has shipped.
- It does not push to `main` outside of Step 4. The ADR-amendments push is the only direct-to-main write — Step 2.5's bookkeeping commit lives on the PR branch and rides the squash. The PR merge itself is GitHub-side.

## When done

The story is merged to `main`, the tracking issue is closed, the feature branch is gone (local + remote), the story file's frontmatter reflects the merged state, any ADR amendments are committed to main, and the operator has a clear next action (the next story).

The story is **shipped**. Subsequent stories that `depends_on: [S-NNN]` will pass the `/modernize-implement` precondition that the dependency's PR is merged into main.
