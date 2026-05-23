---
name: feedback-no-per-blocker-issues
description: "Don't file per-blocker GH issues during /modernize-review. The story file's"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: b7d7b8f8-4929-431f-ae0f-c7dacff1cb25
---

`/modernize-review` should NOT create a separate GitHub issue per `[blocker]` finding. The story file's `## Review` section is the canonical record; per-blocker issues are duplicate state that:

- Adds noise to the issue tracker while a single story is in flight (one PR per story; a 5-blocker review opens 5 issues that all close in the next 30 minutes when the operator rework→fixes them).
- Creates a cleanup obligation at finalize time (see [[pending-boyscout-followups]] — `/modernize-finalize` had to close 7 stale issues for S-013 after the merge).
- Duplicates the annotation that already lives on the bullet in `## Review` (`[in-rework]` / `[deferred → S-XXX]` / `[accepted: ...]`).

**Why:** the user prefers to keep the issue tracker for cross-story / persistent work, not for in-story finding ledgers. The PR itself + the story file are the artifacts; the tracking issue (`#N`) for the story is sufficient as the GitHub-side anchor.

**How to apply:** when implementing the workflow amendment (queued in [[pending-boyscout-followups]]), the correct change is to **remove Step 6 from `/modernize-review` SKILL.md entirely**, not to add issue-closure to `/modernize-finalize`. The story file's `## Review` section + `## Review`-section annotations after rework are the durable record. If the operator wants a follow-up across stories (a deferred finding), `/modernize-rework` already files a follow-up STORY (not an issue) — that's the correct cross-story artifact.

**Exception:** the story's tracking issue (`#N`) created by `/modernize-implement` Step 2 STAYS — that's the GitHub-side anchor for the work, not a per-finding artifact. Only the per-blocker issues are removed.
