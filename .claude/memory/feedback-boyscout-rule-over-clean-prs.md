---
name: feedback-boyscout-rule-over-clean-prs
description: Fix pre-existing bugs in the same PR; trivial cleanups roll into next story; rework meta-improvements never get their own chore/* branch.
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 21c377e9-db57-4689-9f19-8c885e984805
---

When mid-implementation work surfaces pre-existing bugs (broken tests, stale comments, dead code), **fix them in the same PR**. Operator prefers a boyscout-rule PR that leaves the codebase cleaner over single-topic PRs that ship broken things forward.

**Why:** Established 2026-05-16 during S-128. Operator: *"I prefer things being fixed immediately (boyscout rule) over clean PRs that focus on a single topic."* Squash-merge (see [[feedback-always-squash-merge]]) collapses bundled commits into one clean main-tip commit so git history stays readable.

**How to apply:**
- Mechanical / uncontroversial fix: apply in the same PR; mention in commit + PR description so reviewers see in-scope vs. boyscout.
- Architecturally loaded (sacred cows, ADRs, parity contracts): surface via AskUserQuestion first — don't default to "defer."
- Trivial post-merge cleanups (missing stamps, stale SHA refs, doc typos): never open a PR — add to [[pending-boyscout-followups]]; next `/modernize-implement` folds them in. Remove from queue after merge. If queue ≥ 5 items OR one has waited > 3 stories, surface — "next story picks it up" stopped working.
- **`/modernize-rework` Step 3.5 meta-improvements** (workflow / docs / ADR / CONVENTIONS optimizations): NEVER spin `chore/modernize-*` / `chore/conventions-*` / `chore/adr-*` branches. Fold into the current story's PR (or, if it's already merged, the next story's PR via the pending queue). Rationale: avoids branch+PR overhead, keeps the rationale linked to the originating story, and matches the squash-merge model.
