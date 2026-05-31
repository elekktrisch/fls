---
name: modernize-finalize
description: Phase 7 — terminal: docs-prune pass + process proposed ADR amendments + squash-merge PR + delete branch + verify issue closure. One-shot. Trigger: /modernize-finalize S-NNN.
---

# Phase 7 — Story Finalize

Take one implemented story; clean up rotted documentation that the code now sources; merge the PR; archive bookkeeping. One-shot — either succeeds or refuses.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).

## Arg resolution

Two invocation modes:

- **Mode A — story finalize (default):** arg is `S-NNN` (or inferred from current `story/S-NNN-*` branch). Skill: per-story merge into `<base>` (resolved below), archive, cleanup.
- **Mode B — integration finalize:** arg starts with `integration/` (or inferred from current `integration/*` branch). Skill: verify every story in the cluster is `merged: true`, bulk-update each story's `merged_to_main_at`, squash-merge integration → main, delete integration branch. Use this when the integration cluster is ready to land on main.

Story-ID resolution (Mode A) — pattern `^story/S-(\d{3})(-.*)?$` on `git rev-parse --abbrev-ref HEAD`:

- **Arg + branch match** → proceed with the arg.
- **Arg + branch is `story/S-MMM-*` where `MMM ≠ NNN`** → bail: *"current branch is `story/S-MMM-...` but you passed `S-NNN`; switch branch or correct the arg."*
- **Arg + branch isn't a story branch** → proceed with the arg.
- **No arg + branch matches `story/S-NNN-*`** → use the branch's `S-NNN`.
- **No arg + branch matches `integration/*`** → enter Mode B with that branch.
- **No arg + branch doesn't match either** → prompt the operator via `AskUserQuestion` (single question).

## Base branch resolution (Mode A)

`<base>` is resolved from the story frontmatter:

- `integration_base: <branch>` → that branch.
- Else: the repo's default branch (typically `main`).

Drives the squash-merge target + local-cleanup checkout. ADR amendments still commit to `main` directly even when `<base>` is an integration branch — governance is cross-cutting.

## Preconditions

1. Story ID resolved per § Story ID resolution above.
2. Story file at `stories/implemented/S-NNN-*.md` — `/modernize-implement` Step 8 archives the story into `implemented/` as part of the mark-done commit, so it's already there by the time finalize runs. If `merged: true` already → refuse ("already finalized").
3. `status: done`.
4. `github_pr: M` and `gh pr view M --json state` returns `OPEN` (not `MERGED`, not `CLOSED`).
5. PR is `READY_FOR_REVIEW`, not `DRAFT`.
6. `gh pr checks M` shows all green (no `failing` / `in_progress`).
7. No human `CHANGES_REQUESTED` review (best-effort; surface + ask if present).
8. Working tree clean.

Fallback (no `github_pr:`): preconditions 4-7 don't apply. Skill becomes bookkeeping-only — stamp `merged: true`, report. No merge / branch delete.

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

### Step 2.5 — Docs-prune pass

The story body was pruned at mark-done. Step 2.5 broadens the prune to every doc the PR touched **plus the wider doc tree where renamed / moved / deleted symbols leave stale citations**. Cleanly removes the documentation that the shipped code now sources more reliably than the prose.

**Scope:**
- All doc files in the PR diff (anything matching `docs/**`, `*.md`, `CONVENTIONS.md`, `package-info.java`).
- **Plus** grep hits across `docs/**`, `alpenflight/**/*.md`, and every `package-info.java` for any symbol the PR renamed / moved / deleted. Compute the rename list from the PR diff's `R`/`D` rows + class / file / package renames inferred from the diff.

**Per-section disposition** (per the operator's directive — see [[feedback-derive-before-asking]]):

| Section kind | Action |
|---|---|
| References to **unimplemented / future stories or planned features** | KEEP — load-bearing plan content. |
| Doc lives at `docs/modernization/stories/implemented/` | KEEP — that's the historical record of what shipped. |
| Section the code now sources more reliably (file trees, method signatures, DTO field lists, test-method tables, post-decision "Migration cost ~30 min", stale path/symbol citations after a rename) — **clearly rot** | **DELETE** (auto, no prompt). |
| Section the code sources but the prose carries load-bearing why / decision / contract context | KEEP. |
| Unclear / mixed (some lines load-bearing, others rot) | SURFACE to operator. |

**Procedure:**
1. `git fetch origin && git checkout story/S-NNN-<slug> && git pull --ff-only`. Bail "PR branch diverged" if pull fails.
2. Verify the file is already at `implemented/`. If still at top-level `stories/`, the implementer ran the previous (pre-skill-update) flow; do the `git mv` now per the implement-Step-8 trap-guard ordering as a one-off.

3. **Pre-flight grep (Bash, ~5s).** Compute the symbol-rename / file-rename / file-delete list from the PR diff:
   - `git diff --diff-filter=R --name-status <base>..<head>` → file renames (`old-path → new-path`).
   - `git diff --diff-filter=D --name-only <base>..<head>` → file deletions.
   - Class / package renames inferred from inside-file diffs (e.g. `package ch.alpenflight.clubs;` → `package ch.alpenflight.clubs.domain;` on a moved file means the FQN renamed too).
   
   For each old name / path, grep across the prune target tree:
   ```bash
   grep -rln --include='*.md' --include='package-info.java' \
        -e "<old-name-1>" -e "<old-name-2>" ... \
        docs/ alpenflight/ .claude/agents/
   ```
   (Exclude `docs/modernization/stories/implemented/` from results — carve-out by rule.)

4. **Fast-path skip.** If pre-flight grep returns zero hits AND the PR diff contains zero matches in `docs/**` / `*.md` / `CONVENTIONS.md` / `package-info.java`: nothing to prune. Skip directly to step 8 (stamp `merged:` + commit `#N: pre-merge — stamp merged`). The implementer's Step 7 reviewer panel already cleaned up; no second pass needed.

5. **Auto-patch mechanical renames inline.** For each grep hit that is a pure-rename citation (the line contains the old name and replacing it with the new name yields a valid citation), apply the replacement directly via `Edit`. No agent call needed — these are deterministic textual replacements. Examples: `clubs/Club.java` → `clubs/domain/Club.java`; `ClubsRepository` → `JpaClubRepository`; description bullets referencing a deleted skill that have an obvious replacement target.
   - Skip a hit and queue it for the agent if the line carries narrative context that would read wrong after a naive replace (e.g. "ClubsRepository **WAS** a Spring Data interface" — the verb tense matters).

6. **Spawn the agent only when needed.** If the remaining set is non-empty after fast-path + auto-patch — OR the PR-touched docs include sections with rot-pattern signatures (file trees / method signatures / DTO field lists / post-decision migration estimates / test-method tables) — spawn one `tech-writer-reviewer` with:
   - A pre-computed input list of files + line ranges to classify (don't make the agent re-discover scope).
   - The disposition rules above.
   - Output format: `{ auto_delete: [...], surface_to_operator: [...] }`. Carve-outs yield no output.

7. **Apply agent output:**
   - Auto-delete entries → edit each cited file; remove the cited section / lines.
   - `surface_to_operator` non-empty → present as one consolidated `AskUserQuestion` (multi-select); operator picks which to delete; apply the picked deletions.

8. **Stamp + commit.** Update story frontmatter at the `implemented/` path:
   ```yaml
   merged: true
   merged_at: <ISO date>
   ```
   Do **not** stamp `merge_commit:` — SHA isn't known yet; recoverable via `gh pr view M --json mergeCommit`.
   
   Commit subject: `#N: docs prune at finalize — <N sections / K files>` (or `#N: pre-merge — stamp merged` on the fast-path). Push.

9. **Watch CI** on freshened head (`gh run watch --exit-status <latest-run-id>`). Markdown-only diff usually clears in seconds. On red: surface failure + refuse "fix and re-run". Do NOT auto-revert.

If repo allows `gh pr merge --auto`: MAY substitute step 9's watch with `gh pr merge --auto --squash` (queues for green CI). Default flow is explicit watch-then-merge.

**Why the three-tier:** the agent spend per finalize is dominated by scope-discovery (the grep) + classifying mechanical renames. Bash does the first in milliseconds; the auto-patch tier does the second deterministically. The agent only earns its keep on the residual — rot-pattern detection in PR-touched docs + judgment calls on mixed sections. On a clean implement (Step 7's tech-writer-reviewer already caught the drift), Steps 4–7 collapse to a no-op and finalize takes ~10 seconds instead of ~3 minutes.

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

ADR amendments commit to `main` directly — recognised exception to story-per-branch (governance artifacts, operator already approved). Holds even for stories whose `<base>` is an integration branch: ADR governance is cross-cutting and shouldn't be parked on integration. Document in report.

### Step 5 — Verify issue closure + apply labels

1. `gh issue view N --json state` — should be `CLOSED` (auto-close fired on `Closes #N`).
2. If still `OPEN` (rare): `gh issue close N`.
3. Best-effort labels (skip silently if missing): `--add-label status/merged --remove-label status/in-progress,status/done`.
4. Final comment on issue: "Merged in <merge-commit-SHA>. Story file: `docs/modernization/stories/implemented/S-NNN-<slug>.md`."

### Step 6 — Local cleanup

1. `git checkout <base> && git pull --ff-only`.
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

## Mode B procedure — integration → main finalize

Triggered when the arg starts with `integration/` or when invoked from an `integration/*` branch with no story arg.

### B1 — Resolve cluster

1. `<integration-branch>` = arg or current branch.
2. Verify `gh api repos/:owner/:repo/branches/<integration-branch>` exists.
3. Glob `docs/modernization/stories/implemented/*.md` for stories whose frontmatter `integration_base` equals `<integration-branch>`. (Top-level `stories/` may also have entries if a story refined-but-not-yet-implemented uses the cluster — surface those + bail: "story S-MMM is in this cluster but not yet implemented; finish or remove `integration_base` first".)
4. Verify every cluster story has `merged: true` (i.e., its PR was finalized into the integration branch). Surface any with `merged: false`; bail.

### B2 — Confirm + preview

`AskUserQuestion`: list every cluster story (`S-NNN <title>`) + the PR diff range `<integration-branch>...main`. Options: **proceed** / **abort**.

### B3 — Open + merge integration PR

1. `gh pr create --base main --head <integration-branch> --title "<integration-branch>" --body "<body>"` where `<body>` lists each cluster story (`- S-NNN <title>`).
2. Watch CI (`gh pr checks <PR>` until green; refuse to merge red).
3. `gh pr merge <PR> --squash --delete-branch --subject "<integration-branch>: cluster — <S-id>, <S-id>, …" --body "<body>"`.
4. Capture merge commit SHA via `gh pr view <PR> --json mergeCommit`.

### B4 — Bulk-stamp cluster stories

For each cluster story:

1. Edit frontmatter to add `merged_to_main_at: <ISO date>` (alongside the existing `merged_at` which records the integration merge).
2. Stage edits.

Single commit on `main`: `<integration-branch>: stamp merged_to_main on cluster (S-NNN, S-NNN, …)`. Push.

### B5 — Local cleanup

1. `git checkout main && git pull --ff-only`.
2. Remove the local integration branch: `git branch -D <integration-branch>`.
3. `git fetch -p`; verify `git branch -r --list 'origin/<integration-branch>'` returns empty.

### B6 — Report

- Integration branch + URL of squash-merge commit on main.
- Cluster stories merged (count + IDs).
- Story files updated: list with `merged_at` + `merged_to_main_at` per story.
- CI outcome on the final integration PR.
- Next: `/modernize-finalize <next-S-id>` for any remaining non-cluster work.

## Quality bar

- One story per invocation (Mode A) or one integration cluster per invocation (Mode B). One-shot, not iterative.
- Squash by default (per [[feedback-always-squash-merge]]); other strategies are operator overrides.
- ADR amendments are operator-confirmed (never auto-apply).
- ADR amendments commit to main directly (no PR per amendment) — even when the story's `<base>` is an integration branch.
- Verify after every state-changing call (`gh pr view` after merge, `git branch` after delete, `gh issue view` after expected auto-close).
- Local `<base>` (Mode A) / `main` (Mode B) is clean + fast-forwarded; feature branch gone.
- **Mode B refuses if any cluster story is `merged: false`.** Operator must finalize each story into integration first.
- Finalized stories archive to `stories/implemented/`. Mandatory.
- Bookkeeping rides the PR, not a post-merge main commit. Step 2.5 commits + the squash gives ONE commit on main per story (plus optionally one for ADR amendments).
- `merge_commit:` is NOT stamped on frontmatter (recoverable from git log; can't be known pre-merge).
- **Step 2.5 deletes rotted prose** — file trees, method signatures, post-decision migration estimates, stale citations after renames. Carve-outs: future-story plans + the `stories/implemented/` historical record. Unclear sections surface to operator.
- **Step 2.5 is three-tiered for cost:** Bash pre-flight grep (always); auto-patch pure renames inline (no agent); spawn the agent only when rot-pattern signatures or judgment calls remain. Fast-path the whole step when nothing to do.
- Per [[feedback-no-shas-in-committed-docs]]: never embed git SHAs in committed docs. Cite by subject / file:line / PR# / story-ID. SHAs OK in ephemera (issue comments, operator report).

## Not in scope

Code edits. Test runs (PR CI is the gate). Rework iteration. Auto-creating stories. `_ORDER.md` reordering. Force-merging past failing CI / branch protection. Deleting story files (Step 2.5 moves them).
