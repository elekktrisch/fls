---
name: feedback-dirty-refines-go-on-story-branch
description: "When a refine output is uncommitted and a different story's implement requires clean tree, cut the new story branch FIRST and commit the refine on that branch (or stash + pop on the new branch). Never commit to local main without immediately pushing — drift causes the refine to ride someone else's squash."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 60c6c053-e3a6-4f91-ac7c-5232fd92d23a
---

**Rule:** if `/modernize-implement S-NNN` requires clean tree but you have an uncommitted refine output for some unrelated S-MMM, do NOT commit it to local main as a workaround. Either:

1. **Stash** the refine output, cut the story branch off origin/main, start implementation, then `git stash pop` on the implementer's story branch as a separate prep commit. The refine then rides the next implement's PR — which is awkward but explicit.
2. **Better:** push the refine commit to origin/main IMMEDIATELY (and `git pull` so subsequent branches cut off the up-to-date main). Refines are doc-only; landing them on main directly is acceptable as long as origin reflects local.

**Why:** committing to local main without pushing leaves local diverged. When you then cut a story branch from local main, the next PR's diff against origin/main silently includes the unpushed commit. The eventual squash-merge bundles two stories' worth of doc changes into one commit, hiding which work was meant to be which. Drift is real: I did this on 2026-05-17 with S-019's refine output during S-008 implement — the S-019 refine landed via the S-008 squash (`7a953b4`), invisible to the issue tracker and to anyone scanning git log by story prefix.

**How to apply:** at the top of `/modernize-implement`'s preconditions check, if working tree is dirty and the dirt belongs to a different story, push-and-pull or stash-pop on the new branch — never commit-to-local-main as a workaround.
